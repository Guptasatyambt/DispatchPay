package org.myorg.dispatchpay.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myorg.dispatchpay.IntegrationTest;

/** M00.S01: probes are exposed and healthy; nothing else under /actuator is reachable. */
class HealthProbesIT extends IntegrationTest {

    @Test
    void livenessIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readinessIsUpWhenDatabaseIsReachable() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void healthDoesNotExposeComponentDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/info", "/actuator/beans", "/actuator/configprops",
            "/actuator/heapdump", "/actuator/loggers"})
    void otherActuatorEndpointsAreNotExposed(String path) throws Exception {
        // 401 for anonymous callers; authenticated callers get 404 because the endpoints are disabled.
        mockMvc.perform(get(path)).andExpect(status().is4xxClientError());
    }

    @Test
    void unknownApiRouteRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/v1/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
