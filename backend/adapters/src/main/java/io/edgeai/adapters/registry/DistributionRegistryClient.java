package io.edgeai.adapters.registry;

import io.edgeai.domain.aimodel.*;
import io.edgeai.domain.aimodel.RegistryException.Reason;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only client of the one server-configured OCI distribution registry (opencontainers/distribution-spec):
 * catalog/tag listing with explicit {@code last} continuation, manifest GET with the image Accept types and
 * content-digest verification over the returned bytes, and the image config blob for the platform. Redirects are
 * never followed, a Link header is only a "more pages" signal, and response sizes and index depth are bounded.
 */
public final class DistributionRegistryClient implements ImageRegistry, AutoCloseable {
    public static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
    public static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json";
    public static final String DOCKER_MANIFEST = "application/vnd.docker.distribution.manifest.v2+json";
    public static final String DOCKER_LIST = "application/vnd.docker.distribution.manifest.list.v2+json";
    static final Set<String> MANIFESTS = Set.of(OCI_MANIFEST, DOCKER_MANIFEST);
    static final Set<String> INDEXES = Set.of(OCI_INDEX, DOCKER_LIST);
    static final Set<String> CONFIGS = Set.of("application/vnd.oci.image.config.v1+json", "application/vnd.docker.container.image.v1+json");
    static final String ACCEPT = String.join(", ", OCI_INDEX, DOCKER_LIST, OCI_MANIFEST, DOCKER_MANIFEST);
    static final long MAX_LIST_BYTES = 1024 * 1024;
    static final long MAX_MANIFEST_BYTES = 4L * 1024 * 1024;
    static final long MAX_CONFIG_BYTES = 4L * 1024 * 1024;
    static final int MAX_INDEX_ENTRIES = 64;
    static final int MAX_PAGE = 1000;
    private final URI origin;
    private final String host;
    private final Duration timeout;
    private final String authorization;
    private final HttpClient client;
    private final JsonMapper json = new JsonMapper();

    public DistributionRegistryClient(String url, Duration timeout, String username, String password) {
        URI value;
        try { value = URI.create(url.replaceAll("/+$", "")); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Registry URL must be an HTTP(S) origin without credentials"); }
        if (!Set.of("http", "https").contains(value.getScheme()) || value.getHost() == null || value.getUserInfo() != null
            || value.getQuery() != null || value.getFragment() != null || !value.getPath().isEmpty())
            throw new IllegalArgumentException("Registry URL must be an HTTP(S) origin without credentials");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("Registry timeout must be 1s-2m");
        origin = value;
        host = value.getPort() < 0 ? value.getHost() : value.getHost() + ":" + value.getPort();
        this.timeout = timeout;
        authorization = username == null || username.isEmpty() ? null
            : "Basic " + Base64.getEncoder().encodeToString((username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        Duration connect = timeout.compareTo(Duration.ofSeconds(3)) < 0 ? timeout : Duration.ofSeconds(3);
        client = HttpClient.newBuilder().connectTimeout(connect).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public String host() { return host; }

    @Override public RegistryPage repositories(String last, int limit) {
        page(limit);
        if (last != null) ImageReference.repository(last);
        return list("/v2/_catalog", "repositories", last, limit, ImageReference::repository);
    }

    @Override public RegistryPage tags(String repository, String last, int limit) {
        ImageReference.repository(repository);
        page(limit);
        if (last != null) ImageReference.tag(last);
        return list("/v2/" + repository + "/tags/list", "tags", last, limit, ImageReference::tag);
    }

    @Override public ResolvedImage resolve(String repository, String reference) {
        ImageReference.repository(repository);
        ImageReference.reference(reference);
        var manifest = manifest(repository, reference);
        if (manifest.index()) {
            var platforms = new ArrayList<ResolvedImage.PlatformManifest>();
            for (var entry : entries(manifest)) if (entry.image()) platforms.add(entry.descriptor());
            return new ResolvedImage(repository, reference, manifest.digest(), manifest.mediaType(), ResolvedImage.Kind.INDEX, platforms);
        }
        var config = config(repository, manifest);
        return new ResolvedImage(repository, reference, manifest.digest(), manifest.mediaType(), ResolvedImage.Kind.MANIFEST,
            List.of(new ResolvedImage.PlatformManifest(config.platform(), manifest.digest(), manifest.mediaType(), manifest.bytes().length)));
    }

    @Override public VerifiedImage verify(String repository, String digest, ImagePlatform wanted) {
        ImageReference.repository(repository);
        ImageReference.digest(digest);
        var root = manifest(repository, digest);
        if (!root.index()) {
            var config = config(repository, root);
            if (!matches(wanted, config.platform()))
                throw new RegistryException(Reason.PLATFORM_NOT_FOUND, "이미지 설정의 플랫폼(" + config.platform().label() + ")이 선택한 플랫폼(" + wanted.label() + ")과 다릅니다.");
            return verified(repository, root, ResolvedImage.Kind.MANIFEST, root, config);
        }
        var candidates = entries(root).stream().filter(e -> !e.attestation() && e.platform() != null && !e.platform().unknown() && matches(wanted, e.platform())).toList();
        if (candidates.isEmpty()) throw new RegistryException(Reason.PLATFORM_NOT_FOUND, "이미지 index에 선택한 플랫폼(" + wanted.label() + ")이 없습니다.");
        if (candidates.size() > 1) throw new RegistryException(Reason.PLATFORM_NOT_FOUND, "이미지 index에 같은 플랫폼(" + wanted.label() + ")이 여러 개 있어 하나로 정할 수 없습니다.");
        var entry = candidates.get(0);
        if (!entry.image()) throw new RegistryException(Reason.UNSUPPORTED_ARTIFACT, "index 안의 index·지원하지 않는 항목은 등록할 수 없습니다.");
        var child = manifest(repository, entry.descriptor().digest());
        if (child.index()) throw new RegistryException(Reason.UNSUPPORTED_ARTIFACT, "index 안의 index는 등록할 수 없습니다.");
        var config = config(repository, child);
        var declared = entry.platform();
        var actual = config.platform();
        // Both os/architecture must agree; explicit variants must agree too, and an omitted config variant is completed from the index.
        if (!actual.os().equals(declared.os()) || !actual.architecture().equals(declared.architecture())
            || (actual.variant() != null && declared.variant() != null && !actual.variant().equals(declared.variant())))
            throw new RegistryException(Reason.PLATFORM_NOT_FOUND, "index가 선언한 플랫폼(" + declared.label() + ")과 이미지 설정(" + actual.label() + ")이 다릅니다.");
        var platform = new ImagePlatform(actual.os(), actual.architecture(), actual.variant() != null ? actual.variant() : declared.variant());
        return verified(repository, root, ResolvedImage.Kind.INDEX, child, new Config(platform, config.digest(), config.mediaType(), config.created()));
    }

    @Override public void close() { client.close(); }

    // ---- manifests ------------------------------------------------------------------------------------------------

    private record Manifest(String digest, String mediaType, byte[] bytes, JsonNode root) {
        boolean index() { return INDEXES.contains(mediaType); }
    }
    private record Entry(ResolvedImage.PlatformManifest descriptor, ImagePlatform platform, boolean image, boolean attestation) {}
    private record Config(ImagePlatform platform, String digest, String mediaType, String created) {}

    private VerifiedImage verified(String repository, Manifest root, ResolvedImage.Kind kind, Manifest image, Config config) {
        long layers = 0;
        for (var layer : image.root().path("layers")) layers += Math.max(0, layer.path("size").asLong(0));
        return new VerifiedImage(repository, root.digest(), root.mediaType(), kind, config.platform(), image.digest(), image.mediaType(),
            config.digest(), config.mediaType(), layers, config.created());
    }

    private Manifest manifest(String repository, String reference) {
        var response = get("/v2/" + repository + "/manifests/" + reference, null, ACCEPT, MAX_MANIFEST_BYTES);
        String digest = sha256(response.body());
        if (ImageReference.isDigest(reference) && !reference.equals(digest))
            throw new RegistryException(Reason.DIGEST_MISMATCH, "Registry가 반환한 manifest의 sha256이 요청한 digest와 다릅니다.");
        var header = response.headers().firstValue("Docker-Content-Digest").orElse(null);
        if (header != null && header.startsWith("sha256:") && !header.equals(digest))
            throw new RegistryException(Reason.DIGEST_MISMATCH, "Registry가 알린 Docker-Content-Digest와 manifest 내용의 sha256이 다릅니다.");
        JsonNode root;
        try { root = json.readTree(response.body()); }
        catch (RuntimeException e) { throw new RegistryException(Reason.INVALID_RESPONSE, "manifest가 JSON이 아닙니다."); }
        if (!root.isObject()) throw new RegistryException(Reason.INVALID_RESPONSE, "manifest가 JSON 객체가 아닙니다.");
        String mediaType = root.path("mediaType").isString() ? root.path("mediaType").asString()
            : response.headers().firstValue("Content-Type").map(t -> t.split(";")[0].strip()).orElse("");
        if (root.path("schemaVersion").asInt(0) != 2 || !(MANIFESTS.contains(mediaType) || INDEXES.contains(mediaType)))
            throw new RegistryException(Reason.UNSUPPORTED_ARTIFACT, "컨테이너 이미지 manifest(OCI·Docker v2) 또는 index가 아닙니다.");
        if (root.has("artifactType") || (MANIFESTS.contains(mediaType) && !CONFIGS.contains(root.path("config").path("mediaType").asString(""))))
            throw new RegistryException(Reason.UNSUPPORTED_ARTIFACT, "실행 이미지가 아닌 artifact(차트·서명·SBOM 등)는 등록할 수 없습니다.");
        return new Manifest(digest, mediaType, response.body(), root);
    }

    private List<Entry> entries(Manifest index) {
        var manifests = index.root().path("manifests");
        if (!manifests.isArray()) throw new RegistryException(Reason.INVALID_RESPONSE, "index에 manifests 목록이 없습니다.");
        if (manifests.size() > MAX_INDEX_ENTRIES) throw new RegistryException(Reason.TOO_LARGE, "index 항목이 " + MAX_INDEX_ENTRIES + "개를 넘습니다.");
        var result = new ArrayList<Entry>();
        for (var item : manifests) {
            String digest = item.path("digest").asString("");
            String mediaType = item.path("mediaType").asString("");
            if (!ImageReference.isDigest(digest)) continue;
            ImagePlatform platform;
            try {
                var p = item.path("platform");
                platform = p.isObject() ? new ImagePlatform(p.path("os").asString(null), p.path("architecture").asString(null),
                    p.path("variant").isString() && !p.path("variant").asString().isEmpty() ? p.path("variant").asString() : null) : null;
            } catch (IllegalArgumentException invalid) { continue; }
            boolean attestation = "attestation-manifest".equals(item.path("annotations").path("vnd.docker.reference.type").asString(""));
            boolean image = MANIFESTS.contains(mediaType) && platform != null && !platform.unknown() && !attestation;
            var descriptor = platform == null ? null : new ResolvedImage.PlatformManifest(platform, digest, mediaType, Math.max(0, item.path("size").asLong(0)));
            result.add(new Entry(descriptor == null ? new ResolvedImage.PlatformManifest(new ImagePlatform("unknown", "unknown", null), digest, mediaType, 0) : descriptor,
                platform, image, attestation));
        }
        return result;
    }

    private Config config(String repository, Manifest manifest) {
        var descriptor = manifest.root().path("config");
        String digest = descriptor.path("digest").asString("");
        if (!ImageReference.isDigest(digest)) throw new RegistryException(Reason.UNSUPPORTED_ARTIFACT, "이미지 설정 digest가 sha256이 아닙니다.");
        var response = get("/v2/" + repository + "/blobs/" + digest, null, "application/octet-stream, " + String.join(", ", CONFIGS), MAX_CONFIG_BYTES);
        if (!digest.equals(sha256(response.body())))
            throw new RegistryException(Reason.DIGEST_MISMATCH, "이미지 설정 blob의 sha256이 manifest의 config digest와 다릅니다.");
        JsonNode root;
        try { root = json.readTree(response.body()); }
        catch (RuntimeException e) { throw new RegistryException(Reason.INVALID_RESPONSE, "이미지 설정이 JSON이 아닙니다."); }
        if (!root.isObject()) throw new RegistryException(Reason.INVALID_RESPONSE, "이미지 설정이 JSON 객체가 아닙니다.");
        ImagePlatform platform;
        try {
            platform = new ImagePlatform(root.path("os").asString(null), root.path("architecture").asString(null),
                root.path("variant").isString() && !root.path("variant").asString().isEmpty() ? root.path("variant").asString() : null);
        } catch (IllegalArgumentException invalid) {
            throw new RegistryException(Reason.UNSUPPORTED_ARTIFACT, "이미지 설정에 OS·아키텍처가 없습니다.");
        }
        String created = root.path("created").isString() && root.path("created").asString().length() <= 64 ? root.path("created").asString() : null;
        return new Config(platform, digest, descriptor.path("mediaType").asString(), created);
    }

    private static boolean matches(ImagePlatform wanted, ImagePlatform actual) {
        return wanted.os().equals(actual.os()) && wanted.architecture().equals(actual.architecture())
            && (wanted.variant() == null || wanted.variant().equals(actual.variant()));
    }

    // ---- listing --------------------------------------------------------------------------------------------------

    private static void page(int limit) {
        if (limit < 1 || limit > MAX_PAGE) throw new IllegalArgumentException("Page size must be 1-" + MAX_PAGE);
    }

    private RegistryPage list(String path, String field, String last, int limit, java.util.function.UnaryOperator<String> validate) {
        String query = "n=" + limit + (last == null ? "" : "&last=" + URLEncoder.encode(last, StandardCharsets.UTF_8));
        var response = get(path, query, "application/json", MAX_LIST_BYTES);
        JsonNode root;
        try { root = json.readTree(response.body()); }
        catch (RuntimeException e) { throw new RegistryException(Reason.INVALID_RESPONSE, "Registry 목록 응답이 JSON이 아닙니다."); }
        if (!root.isObject()) throw new RegistryException(Reason.INVALID_RESPONSE, "Registry 목록 응답이 JSON 객체가 아닙니다.");
        var values = root.path(field);
        var items = new ArrayList<String>();
        if (values.isArray()) {
            if (values.size() > limit) throw new RegistryException(Reason.INVALID_RESPONSE, "Registry가 요청보다 많은 항목을 반환했습니다.");
            for (var value : values) {
                try { items.add(validate.apply(value.asString(null))); }
                catch (IllegalArgumentException invalid) { throw new RegistryException(Reason.INVALID_RESPONSE, "Registry 목록에 형식이 맞지 않는 이름이 있습니다."); }
            }
        } else if (!values.isNull() && !values.isMissingNode()) throw new RegistryException(Reason.INVALID_RESPONSE, "Registry 목록 형식이 아닙니다.");
        // The Link URL itself is never requested: the next page is always "last=<final item>" on the configured origin.
        boolean more = response.headers().allValues("Link").stream().anyMatch(l -> l.contains("rel=\"next\"") || l.contains("rel=next"));
        return new RegistryPage(items, more && !items.isEmpty() ? items.get(items.size() - 1) : null);
    }

    // ---- HTTP -----------------------------------------------------------------------------------------------------

    private HttpResponse<byte[]> get(String path, String query, String accept, long maxBytes) {
        URI uri = URI.create(origin + path + (query == null ? "" : "?" + query));
        var builder = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", accept).GET();
        if (authorization != null) builder.header("Authorization", authorization);
        HttpResponse<byte[]> response;
        try { response = client.send(builder.build(), info -> new LimitedBody(maxBytes)); }
        catch (HttpTimeoutException timedOut) { throw new RegistryException(Reason.TIMEOUT, "Registry 응답이 제한 시간(" + timeout.toSeconds() + "초) 안에 오지 않았습니다."); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RegistryException(Reason.UNAVAILABLE, "Registry 조회가 중단되었습니다.");
        } catch (IOException failed) {
            for (Throwable cause = failed; cause != null; cause = cause.getCause())
                if (cause instanceof TooLarge) throw new RegistryException(Reason.TOO_LARGE, "Registry 응답이 허용 크기를 넘습니다.");
            throw new RegistryException(Reason.UNAVAILABLE, "Registry(" + host + ")에 연결할 수 없습니다.");
        }
        int status = response.statusCode();
        if (status == 200) return response;
        if (status >= 300 && status < 400) throw new RegistryException(Reason.REDIRECT_BLOCKED, "Registry가 다른 위치로 이동을 요청했으나 따르지 않습니다(HTTP " + status + ").");
        if (status == 401 || status == 403) throw new RegistryException(Reason.UNAUTHORIZED, "Registry가 접근을 거부했습니다(HTTP " + status + "). 서버의 Registry 인증 설정을 확인하세요.");
        if (status == 404) throw new RegistryException(Reason.NOT_FOUND, "Registry에 해당 저장소·태그·digest가 없습니다.");
        throw new RegistryException(Reason.UNAVAILABLE, "Registry가 HTTP " + status + "로 응답했습니다.");
    }

    static String sha256(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static final class TooLarge extends IOException { TooLarge() { super("Registry response too large"); } }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final long max;
        private Flow.Subscription subscription;
        private long size;
        private boolean failed;
        LimitedBody(long max) { this.max = max; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> values) {
            if (failed) return;
            for (var value : values) size += value.remaining();
            if (size > max) { failed = true; subscription.cancel(); delegate.onError(new TooLarge()); }
            else delegate.onNext(values);
        }
        public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
