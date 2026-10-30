package io.edgeai.adapters.registry;

import io.edgeai.domain.aimodel.*;
import io.edgeai.domain.aimodel.RegistryException.Reason;
import java.time.Duration;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** The registry adapter over real HTTP against an in-process OCI distribution endpoint. */
class DistributionRegistryClientTest {
    static FakeRegistry registry;
    DistributionRegistryClient client;

    @BeforeAll static void start() throws Exception { registry = new FakeRegistry(); }
    @AfterAll static void stop() { registry.close(); }
    @BeforeEach void reset() {
        registry.reset();
        client = new DistributionRegistryClient(registry.url(), Duration.ofSeconds(2), null, null);
    }
    @AfterEach void close() { client.close(); }

    private static Reason reason(ThrowableAssert.ThrowingCallable call) {
        var thrown = catchThrowableOfType(RegistryException.class, call);
        assertThat(thrown).as("RegistryException").isNotNull();
        return thrown.reason();
    }

    @Test void catalogAndTagsPageWithExplicitContinuationAndNeverFollowLinkUrls() {
        for (String repo : new String[]{"edgeai/a", "edgeai/b", "edgeai/c"}) registry.image(repo, "v1", "linux", "amd64");
        var first = client.repositories(null, 2);
        assertThat(first.items()).containsExactly("edgeai/a", "edgeai/b");
        assertThat(first.next()).isEqualTo("edgeai/b");
        var second = client.repositories(first.next(), 2);
        assertThat(second.items()).containsExactly("edgeai/c");
        assertThat(second.next()).isNull();
        registry.manifest("edgeai/a", "v2", FakeRegistry.OCI_MANIFEST, "{}");
        registry.manifest("edgeai/a", "v3", FakeRegistry.OCI_MANIFEST, "{}");
        var tags = client.tags("edgeai/a", null, 2);
        assertThat(tags.items()).containsExactly("v1", "v2");
        assertThat(tags.next()).isEqualTo("v2");
        assertThat(client.tags("edgeai/a", "v2", 2).items()).containsExactly("v3");
        // A Link pointing elsewhere is only a "more" signal; the client asks the configured registry with last=.
        registry.override = e -> {
            if (!e.getRequestURI().getPath().equals("/v2/_catalog")) return false;
            e.getResponseHeaders().add("Link", "<http://169.254.169.254/latest/meta-data?last=x>; rel=\"next\"");
            FakeRegistry.send(e, 200, "application/json", "{\"repositories\":[\"edgeai/a\"]}", null);
            return true;
        };
        assertThat(client.repositories(null, 1).next()).isEqualTo("edgeai/a");
        assertThat(registry.requests).noneMatch(r -> r.contains("meta-data"));
        assertThat(registry.requests).anyMatch(r -> r.startsWith("GET /v2/_catalog?n=2&last=edgeai%2Fb") || r.startsWith("GET /v2/_catalog?n=2&last=edgeai/b"));
    }

    @Test void singleManifestIsDigestVerifiedAndItsPlatformComesFromTheConfigBlob() {
        var image = registry.image("edgeai/detector", "1.0", "linux", "arm64");
        var resolved = client.resolve("edgeai/detector", "1.0");
        assertThat(resolved.kind()).isEqualTo(ResolvedImage.Kind.MANIFEST);
        assertThat(resolved.digest()).isEqualTo(image.digest());
        assertThat(resolved.platforms()).singleElement().satisfies(p -> {
            assertThat(p.platform().label()).isEqualTo("linux/arm64");
            assertThat(p.digest()).isEqualTo(image.digest());
        });
        assertThat(registry.requests).anyMatch(r -> r.startsWith("GET /v2/edgeai/detector/manifests/1.0") && r.contains(FakeRegistry.OCI_INDEX) && r.contains(FakeRegistry.OCI_MANIFEST)
            && r.contains("application/vnd.docker.distribution.manifest.v2+json") && r.contains("application/vnd.docker.distribution.manifest.list.v2+json"));
        var verified = client.verify("edgeai/detector", image.digest(), new ImagePlatform("linux", "arm64", null));
        assertThat(verified.platformManifestDigest()).isEqualTo(image.digest());
        assertThat(verified.configMediaType()).isEqualTo(FakeRegistry.OCI_CONFIG);
        assertThat(verified.layersSize()).isEqualTo(1234);
        assertThat(reason(() -> client.verify("edgeai/detector", image.digest(), new ImagePlatform("linux", "amd64", null)))).isEqualTo(Reason.PLATFORM_NOT_FOUND);
    }

    @Test void indexListsOnlyDeclaredPlatformsWithoutAttestationsAndVerifiesTheSelectedChildConfig() {
        var index = registry.multiPlatform("edgeai/detector", "2.0");
        var resolved = client.resolve("edgeai/detector", "2.0");
        assertThat(resolved.kind()).isEqualTo(ResolvedImage.Kind.INDEX);
        assertThat(resolved.digest()).isEqualTo(index.digest());
        assertThat(resolved.platforms()).extracting(p -> p.platform().label()).containsExactly("linux/amd64", "linux/arm64/v8");
        var arm = resolved.platforms().get(1);
        var verified = client.verify("edgeai/detector", index.digest(), new ImagePlatform("linux", "arm64", "v8"));
        assertThat(verified.digest()).isEqualTo(index.digest());
        assertThat(verified.kind()).isEqualTo(ResolvedImage.Kind.INDEX);
        assertThat(verified.platformManifestDigest()).isEqualTo(arm.digest()).isNotEqualTo(index.digest());
        assertThat(verified.platform().architecture()).isEqualTo("arm64");
        assertThat(reason(() -> client.verify("edgeai/detector", index.digest(), new ImagePlatform("unknown", "unknown", null)))).isEqualTo(Reason.PLATFORM_NOT_FOUND);
        // The child config disagreeing with the index descriptor is rejected rather than trusted.
        var lying = registry.manifest("edgeai/lying", "1", FakeRegistry.OCI_INDEX, "{\"schemaVersion\":2,\"mediaType\":\"" + FakeRegistry.OCI_INDEX
            + "\",\"manifests\":[{\"mediaType\":\"" + FakeRegistry.OCI_MANIFEST + "\",\"digest\":\"" + registry.image("edgeai/lying", null, "linux", "amd64").digest()
            + "\",\"size\":10,\"platform\":{\"os\":\"linux\",\"architecture\":\"arm64\"}}]}");
        assertThat(reason(() -> client.verify("edgeai/lying", lying.digest(), new ImagePlatform("linux", "arm64", null)))).isEqualTo(Reason.PLATFORM_NOT_FOUND);
    }

    @Test void bodiesThatDoNotHashToTheDigestAreRejected() {
        var image = registry.image("edgeai/detector", "1.0", "linux", "amd64");
        var tampered = new FakeRegistry.Content(image.mediaType(), (new String(image.bytes()) + " ").getBytes(), image.digest());
        registry.manifests.put("edgeai/detector@" + image.digest(), tampered);
        registry.manifests.put("edgeai/detector:1.0", tampered);
        assertThat(reason(() -> client.resolve("edgeai/detector", "1.0"))).isEqualTo(Reason.DIGEST_MISMATCH);
        assertThat(reason(() -> client.verify("edgeai/detector", image.digest(), new ImagePlatform("linux", "amd64", null)))).isEqualTo(Reason.DIGEST_MISMATCH);
        // A tag response whose Docker-Content-Digest header disagrees with the body is rejected too.
        var other = registry.image("edgeai/other", "1", "linux", "amd64");
        registry.override = e -> {
            if (!e.getRequestURI().getPath().equals("/v2/edgeai/other/manifests/1")) return false;
            e.getResponseHeaders().add("Docker-Content-Digest", "sha256:" + "f".repeat(64));
            FakeRegistry.send(e, 200, other.mediaType(), null, other.bytes());
            return true;
        };
        assertThat(reason(() -> client.resolve("edgeai/other", "1"))).isEqualTo(Reason.DIGEST_MISMATCH);
    }

    @Test void indexAndConfigVariantsMustAgreeAndAMissingConfigVariantIsCompletedFromTheIndex() {
        var v6 = registry.blob("edgeai/arm", "{\"architecture\":\"arm\",\"os\":\"linux\",\"variant\":\"v6\",\"config\":{}}");
        var v6manifest = registry.manifest("edgeai/arm", null, FakeRegistry.OCI_MANIFEST, "{\"schemaVersion\":2,\"mediaType\":\"" + FakeRegistry.OCI_MANIFEST
            + "\",\"config\":{\"mediaType\":\"" + FakeRegistry.OCI_CONFIG + "\",\"digest\":\"" + v6.digest() + "\",\"size\":" + v6.bytes().length + "},\"layers\":[]}");
        var claimsV7 = registry.manifest("edgeai/arm", "1", FakeRegistry.OCI_INDEX, "{\"schemaVersion\":2,\"mediaType\":\"" + FakeRegistry.OCI_INDEX
            + "\",\"manifests\":[{\"mediaType\":\"" + FakeRegistry.OCI_MANIFEST + "\",\"digest\":\"" + v6manifest.digest()
            + "\",\"size\":10,\"platform\":{\"os\":\"linux\",\"architecture\":\"arm\",\"variant\":\"v7\"}}]}");
        assertThat(reason(() -> client.verify("edgeai/arm", claimsV7.digest(), new ImagePlatform("linux", "arm", "v7")))).isEqualTo(Reason.PLATFORM_NOT_FOUND);
        // The fixture index declares arm64/v8 while the child config omits the variant: the index variant completes it.
        var index = registry.multiPlatform("edgeai/detector", "2.0");
        var verified = client.verify("edgeai/detector", index.digest(), new ImagePlatform("linux", "arm64", null));
        assertThat(verified.platform().variant()).isEqualTo("v8");
        // A single manifest's own config variant is authoritative for an explicit request.
        assertThat(reason(() -> client.verify("edgeai/arm", v6manifest.digest(), new ImagePlatform("linux", "arm", "v7")))).isEqualTo(Reason.PLATFORM_NOT_FOUND);
    }

    @Test void jsonNullOrNonObjectBodiesAreInvalidResponsesNotCrashes() {
        registry.manifest("edgeai/null", "1", FakeRegistry.OCI_MANIFEST, "null");
        assertThat(reason(() -> client.resolve("edgeai/null", "1"))).isEqualTo(Reason.INVALID_RESPONSE);
        var config = registry.blob("edgeai/nullconfig", "null");
        registry.manifest("edgeai/nullconfig", "1", FakeRegistry.OCI_MANIFEST, "{\"schemaVersion\":2,\"mediaType\":\"" + FakeRegistry.OCI_MANIFEST
            + "\",\"config\":{\"mediaType\":\"" + FakeRegistry.OCI_CONFIG + "\",\"digest\":\"" + config.digest() + "\",\"size\":4},\"layers\":null}");
        assertThat(reason(() -> client.resolve("edgeai/nullconfig", "1"))).isEqualTo(Reason.INVALID_RESPONSE);
        registry.manifest("edgeai/nullindex", "1", FakeRegistry.OCI_INDEX, "{\"schemaVersion\":2,\"mediaType\":\"" + FakeRegistry.OCI_INDEX + "\",\"manifests\":[null,1,\"x\"]}");
        assertThat(client.resolve("edgeai/nullindex", "1").platforms()).isEmpty();
        registry.override = e -> {
            if (!e.getRequestURI().getPath().equals("/v2/_catalog")) return false;
            FakeRegistry.send(e, 200, "application/json", "null", null);
            return true;
        };
        assertThat(reason(() -> client.repositories(null, 10))).isEqualTo(Reason.INVALID_RESPONSE);
    }

    @Test void unsupportedArtifactsAndNestedIndexesAreRejected() {
        var helm = registry.manifest("charts/app", "1", FakeRegistry.OCI_MANIFEST, "{\"schemaVersion\":2,\"mediaType\":\"" + FakeRegistry.OCI_MANIFEST
            + "\",\"config\":{\"mediaType\":\"application/vnd.cncf.helm.config.v1+json\",\"digest\":\"sha256:" + "3".repeat(64) + "\",\"size\":2},\"layers\":[]}");
        assertThat(reason(() -> client.resolve("charts/app", "1"))).isEqualTo(Reason.UNSUPPORTED_ARTIFACT);
        assertThat(reason(() -> client.verify("charts/app", helm.digest(), new ImagePlatform("linux", "amd64", null)))).isEqualTo(Reason.UNSUPPORTED_ARTIFACT);
        registry.manifest("edgeai/artifact", "1", FakeRegistry.OCI_MANIFEST, "{\"schemaVersion\":2,\"mediaType\":\"" + FakeRegistry.OCI_MANIFEST
            + "\",\"artifactType\":\"application/vnd.example.sbom\",\"config\":{\"mediaType\":\"application/vnd.oci.empty.v1+json\",\"digest\":\"sha256:" + "4".repeat(64) + "\",\"size\":2},\"layers\":[]}");
        assertThat(reason(() -> client.resolve("edgeai/artifact", "1"))).isEqualTo(Reason.UNSUPPORTED_ARTIFACT);
        registry.manifest("edgeai/schema1", "1", "application/vnd.docker.distribution.manifest.v1+prettyjws", "{\"schemaVersion\":1}");
        assertThat(reason(() -> client.resolve("edgeai/schema1", "1"))).isEqualTo(Reason.UNSUPPORTED_ARTIFACT);
        var inner = registry.multiPlatform("edgeai/nested", null);
        var outer = registry.manifest("edgeai/nested", "1", FakeRegistry.OCI_INDEX, "{\"schemaVersion\":2,\"mediaType\":\"" + FakeRegistry.OCI_INDEX
            + "\",\"manifests\":[{\"mediaType\":\"" + FakeRegistry.OCI_INDEX + "\",\"digest\":\"" + inner.digest() + "\",\"size\":10,\"platform\":{\"os\":\"linux\",\"architecture\":\"amd64\"}}]}");
        assertThat(reason(() -> client.verify("edgeai/nested", outer.digest(), new ImagePlatform("linux", "amd64", null)))).isEqualTo(Reason.UNSUPPORTED_ARTIFACT);
        assertThat(client.resolve("edgeai/nested", "1").platforms()).isEmpty();
    }

    @Test void notFoundRedirectAuthTimeoutAndOversizeAreDistinctAndCredentialFree() {
        assertThat(reason(() -> client.resolve("edgeai/missing", "1"))).isEqualTo(Reason.NOT_FOUND);
        assertThat(reason(() -> client.tags("edgeai/missing", null, 10))).isEqualTo(Reason.NOT_FOUND);
        registry.override = e -> {
            String p = e.getRequestURI().getPath();
            try {
                if (p.contains("/redirect/")) { e.getResponseHeaders().add("Location", "http://169.254.169.254/x"); FakeRegistry.send(e, 307, "text/plain", "", null); return true; }
                if (p.contains("/denied/")) { FakeRegistry.send(e, 401, "application/json", "{\"errors\":[{\"code\":\"UNAUTHORIZED\"}]}", null); return true; }
                if (p.contains("/slow/")) { Thread.sleep(4000); FakeRegistry.send(e, 200, "application/json", "{}", null); return true; }
                if (p.contains("/huge/")) { FakeRegistry.send(e, 200, FakeRegistry.OCI_MANIFEST, null, new byte[5 * 1024 * 1024]); return true; }
            } catch (Exception ignored) { return true; }
            return false;
        };
        assertThat(reason(() -> client.resolve("edgeai/redirect", "1"))).isEqualTo(Reason.REDIRECT_BLOCKED);
        assertThat(registry.requests).noneMatch(r -> r.contains("/x "));
        var secret = new DistributionRegistryClient(registry.url(), Duration.ofSeconds(1), "robot", "PRIVATE-CANARY-SECRET");
        try {
            var thrown = catchThrowableOfType(RegistryException.class, () -> secret.resolve("edgeai/denied", "1"));
            assertThat(thrown.reason()).isEqualTo(Reason.UNAUTHORIZED);
            assertThat(thrown.getMessage()).doesNotContain("PRIVATE-CANARY-SECRET").doesNotContain("robot");
            assertThat(registry.authorizations).anyMatch(a -> a.startsWith("Basic "));
            assertThat(secret.host()).isEqualTo(registry.host());
            var slow = catchThrowableOfType(RegistryException.class, () -> secret.resolve("edgeai/slow", "1"));
            assertThat(slow.reason()).isEqualTo(Reason.TIMEOUT);
        } finally { secret.close(); }
        assertThat(reason(() -> client.resolve("edgeai/huge", "1"))).isEqualTo(Reason.TOO_LARGE);
        var down = new DistributionRegistryClient("http://127.0.0.1:1", Duration.ofSeconds(1), null, null);
        try { assertThat(reason(() -> down.repositories(null, 10))).isEqualTo(Reason.UNAVAILABLE); } finally { down.close(); }
    }

    @Test void aBodyThatStallsAfterTheHeadersTimesOutWithinTheBudgetAndReleasesTheConnection() throws Exception {
        var closed = new java.util.concurrent.CountDownLatch(1);
        registry.override = e -> {
            if (!e.getRequestURI().getPath().contains("/stall/")) return false;
            e.getResponseHeaders().add("Content-Type", FakeRegistry.OCI_MANIFEST);
            e.sendResponseHeaders(200, 0); // chunked: headers now, body later
            var body = e.getResponseBody();
            body.write('{'); body.flush();
            try {
                // The body never completes within 8 s; a write fails once the client abandons the exchange.
                for (int i = 0; i < 80; i++) { Thread.sleep(100); body.write(' '); body.flush(); }
            } catch (java.io.IOException released) { closed.countDown(); }
            return true;
        };
        var slow = new DistributionRegistryClient(registry.url(), Duration.ofSeconds(1), null, null);
        try {
            long started = System.nanoTime();
            assertThat(reason(() -> slow.resolve("edgeai/stall", "1"))).isEqualTo(Reason.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
            assertThat(closed.await(5, java.util.concurrent.TimeUnit.SECONDS)).as("connection released by the client").isTrue();
        } finally { slow.close(); }
    }

    @Test void onlyAServerOriginIsAcceptedAndInputsAreValidatedBeforeAnyRequest() {
        for (String bad : new String[]{"ftp://registry", "http://user:pw@registry:5000", "http://registry:5000/v2", "http://registry:5000?x=1", "registry:5000"})
            assertThatIllegalArgumentException().as(bad).isThrownBy(() -> new DistributionRegistryClient(bad, Duration.ofSeconds(2), null, null));
        for (String repo : new String[]{"../etc", "Edgeai/x", "edgeai//x", "edgeai/x?y", "http://evil/x", "a".repeat(256)})
            assertThatIllegalArgumentException().as(repo).isThrownBy(() -> client.tags(repo, null, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> client.resolve("edgeai/x", "../../v2/_catalog"));
        assertThatIllegalArgumentException().isThrownBy(() -> client.resolve("edgeai/x", "sha512:" + "a".repeat(128)));
        assertThatIllegalArgumentException().isThrownBy(() -> client.repositories(null, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> client.repositories(null, 1001));
        assertThatIllegalArgumentException().isThrownBy(() -> client.repositories("Bad Repo", 10));
        assertThat(registry.requests).isEmpty();
    }
}
