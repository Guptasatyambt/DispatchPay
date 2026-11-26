package org.myorg.dispatchpay.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.myorg.dispatchpay.StorageTestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** M00.S01 gate: readiness fails when a required dependency is unavailable; liveness does not. */
@SpringBootTest(properties = "spring.datasource.hikari.connection-timeout=1000")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers
@Import(StorageTestConfiguration.class)
class ReadinessWithoutDatabaseIT {

    // Dedicated container: this test stops it, so it must not be shared with other test contexts.
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mockMvc;

    @Test
    void readinessReportsOutOfServiceWhenDatabaseStops() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());

        postgres.stop();

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }
}
