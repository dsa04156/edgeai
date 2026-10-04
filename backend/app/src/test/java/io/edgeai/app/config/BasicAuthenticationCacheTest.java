package io.edgeai.app.config;

import io.edgeai.app.controller.PlatformController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PlatformController.class, properties = {
    "spring.security.user.name=cache-user", "spring.security.user.password=cache-test-password"})
@Import(SecurityConfiguration.class)
class BasicAuthenticationCacheTest {
    @Autowired MockMvc mvc;
    @Autowired UserDetailsManager users;

    @Test void warmCacheStillRequiresCorrectBasicOnEachRequestAndCsrfOnWrites() throws Exception {
        var session = new MockHttpSession();
        for (int i = 0; i < 3; i++)
            mvc.perform(get("/api/v1/platform").session(session).with(httpBasic("cache-user", "cache-test-password")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/platform").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform").session(session).with(httpBasic("cache-user", "wrong")))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform").with(httpBasic("unknown-user", "cache-test-password")))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/platform").with(httpBasic("cache-user", "cache-test-password")))
            .andExpect(status().isForbidden());
    }

    @Test void passwordRotationRolesAndDisableTakeEffectDespiteWarmCache() throws Exception {
        String name = "rotating-user";
        users.createUser(User.withUsername(name).password("{noop}before").roles("USER").build());
        try {
            for (int i = 0; i < 3; i++)
                mvc.perform(get("/api/v1/platform").with(httpBasic(name, "before"))).andExpect(status().isOk());
            users.updateUser(User.withUsername(name).password("{noop}after").roles("READER").build());
            mvc.perform(get("/api/v1/platform").with(httpBasic(name, "before"))).andExpect(status().isUnauthorized());
            for (int i = 0; i < 3; i++)
                mvc.perform(get("/api/v1/platform").with(httpBasic(name, "after")))
                    .andExpect(status().isOk()).andExpect(authenticated().withRoles("READER"));
            users.updateUser(User.withUserDetails(users.loadUserByUsername(name)).roles("OPERATOR").build());
            mvc.perform(get("/api/v1/platform").with(httpBasic(name, "after")))
                .andExpect(status().isOk()).andExpect(authenticated().withRoles("OPERATOR"));
            users.updateUser(User.withUserDetails(users.loadUserByUsername(name)).accountLocked(true).build());
            mvc.perform(get("/api/v1/platform").with(httpBasic(name, "after"))).andExpect(status().isUnauthorized());
            users.updateUser(User.withUserDetails(users.loadUserByUsername(name)).accountLocked(false).disabled(true).build());
            mvc.perform(get("/api/v1/platform").with(httpBasic(name, "after"))).andExpect(status().isUnauthorized());
        } finally { users.deleteUser(name); }
    }
}
