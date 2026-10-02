package io.edgeai.app.profile;

import io.edgeai.domain.profile.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.security.user.name=profile-test", "spring.security.user.password=profile-test-only"})
@AutoConfigureMockMvc
class ProfileIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired ProfileService service;
    private final JsonMapper json = new JsonMapper();
    private String key() { return "test-" + UUID.randomUUID(); }
    private String body(String key, String version, String spec) {
        return "{\"key\":\"" + key + "\",\"version\":\"" + version + "\",\"spec\":" + spec + "}";
    }
    @Test void publishesAllKindsWithActualAuthCsrfAndReadsThemBack() throws Exception {
        var tokenResponse = mvc.perform(get("/api/v1/csrf").with(httpBasic("profile-test", "profile-test-only")))
            .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) tokenResponse.getRequest().getSession(false);
        String token = json.readTree(tokenResponse.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(get("/api/v1/profiles/DEVICE").session(session)).andExpect(status().isUnauthorized());
        String key = key();
        for (String kind : List.of("DEVICE", "SERVICE", "VD")) {
            String url = "/api/v1/profiles/" + kind;
            String body = body(key, "1.0.0", "{\"protocol\":\"mqtt\",\"value\":1}");
            var response = mvc.perform(post(url).session(session).with(httpBasic("profile-test", "profile-test-only"))
                .header("X-CSRF-TOKEN", token).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value(kind))
                .andExpect(header().string("Location", url + "/" + key + "/versions/1.0.0")).andReturn();
            String id = json.readTree(response.getResponse().getContentAsString()).get("id").asText();
            mvc.perform(post(url).with(user("test")).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
            mvc.perform(post(url).with(user("test")).with(csrf()).contentType("application/json").content(body.replace("mqtt", "http")))
                .andExpect(status().isConflict());
            mvc.perform(get(url + "/" + key + "/versions/1.0.0").with(user("test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.spec.protocol").value("mqtt"))
                .andExpect(jsonPath("$.kind").value(kind));
            mvc.perform(get(url + "?key=" + key).with(user("test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].kind").value(kind));
        }
    }
    @Test void paginatesVersionsAndValidatesRequests() throws Exception {
        String key = key(), url = "/api/v1/profiles/DEVICE";
        for (String version : List.of("1.0.0", "2.0.0", "10.0.0"))
            service.publish(ProfileIdentity.Kind.DEVICE, body(key, version, "{\"n\":1}"));
        mvc.perform(get(url + "?key=" + key + "&limit=2").with(user("test")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].version").value("1.0.0"))
            .andExpect(jsonPath("$.items[1].version").value("10.0.0")).andExpect(jsonPath("$.nextOffset").value(2));
        mvc.perform(get(url + "?key=" + key + "&limit=2&offset=2").with(user("test")))
            .andExpect(jsonPath("$.items[0].version").value("2.0.0")).andExpect(jsonPath("$.nextOffset").isEmpty());
        mvc.perform(get(url + "/" + key + "/versions/9.0.0").with(user("test"))).andExpect(status().isNotFound());
        for (String query : List.of("limit=0", "limit=101", "offset=-1", "key=UPPER"))
            mvc.perform(get(url + "?" + query).with(user("test"))).andExpect(status().isBadRequest());
        for (String spec : List.of("{}", "[]", "{\"a\":1,\"a\":2}"))
            mvc.perform(post(url).with(user("test")).with(csrf()).contentType("application/json").content(body(key(), "1.0.0", spec)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).with(user("test")).with(csrf()).contentType("application/json").content(body(key(), "1.0.0", "{\"x\":\"" + "a".repeat(66000) + "\"}")))
            .andExpect(status().isPayloadTooLarge());
    }
    @Test void concurrentReplaysHaveOneRowAndOneCreatedResponse() throws Exception {
        String key = key();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var gate = new CountDownLatch(1);
            List<Future<ProfileRepository.Publication>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) calls.add(pool.submit(() -> {
                gate.await(); return service.publish(ProfileIdentity.Kind.DEVICE, body(key, "1.0.0", "{\"x\":1}"));
            }));
            gate.countDown();
            List<ProfileRepository.Publication> results = new ArrayList<>();
            for (var call : calls) results.add(call.get(20, TimeUnit.SECONDS));
            assertThat(results.stream().filter(ProfileRepository.Publication::created).count()).isEqualTo(1);
            assertThat(results.stream().map(r -> r.version().id()).distinct().count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.profile_version WHERE profile_key = ?", Integer.class, key)).isEqualTo(1);
    }
    @Test void concurrentDifferentContentCannotReplaceTheWinner() throws Exception {
        String key = key();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var calls = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) {
                final int value = i;
                calls.add(pool.submit(() -> {
                    gate.await();
                    try { return service.publish(ProfileIdentity.Kind.SERVICE, body(key, "1.0.0", "{\"n\":" + value + "}")).created(); }
                    catch (ProfileService.Conflict e) { return false; }
                }));
            }
            gate.countDown();
            assertThat(List.of(calls.get(0).get(20, TimeUnit.SECONDS), calls.get(1).get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }
    }
    @Test void databaseRejectsMutationAndDuplicateIdentities() {
        var value = service.publish(ProfileIdentity.Kind.VD, body(key(), "1.0.0", "{\"type\":\"sensorMirror\"}")).version();
        assertThatThrownBy(() -> jdbc.update("UPDATE edgeai.profile_version SET spec = '{\"changed\":true}' WHERE id = ?", value.id()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM edgeai.profile_version WHERE id = ?", value.id()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
            .executeWithoutResult(status -> {
                status.setRollbackOnly(); // Never remove data, even if the guard regresses.
                jdbc.execute("TRUNCATE edgeai.profile_version");
            }))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO edgeai.profile_version SELECT ?, kind, profile_key, version, spec, digest, created_at FROM edgeai.profile_version WHERE id = ?", UUID.randomUUID(), value.id()))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(service.find(value.identity()).digest()).isEqualTo(value.digest());
    }
}
