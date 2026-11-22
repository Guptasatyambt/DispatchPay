package org.myorg.dispatchpay.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.myorg.dispatchpay.IntegrationTest;

class CorrelationIdIT extends IntegrationTest {

    @Test
    void generatesCorrelationIdWhenAbsent() throws Exception {
        String id = mockMvc.perform(get("/actuator/health/liveness"))
                .andReturn().getResponse().getHeader(CorrelationId.HEADER);

        assertThat(id).matches("[0-9a-f-]{36}");
    }

    @Test
    void echoesWellFormedClientCorrelationId() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness").header(CorrelationId.HEADER, "client-req-12345"))
                .andExpect(header().string(CorrelationId.HEADER, "client-req-12345"));
    }

    @Test
    void replacesMalformedClientCorrelationId() throws Exception {
        String id = mockMvc.perform(get("/actuator/health/liveness")
                        .header(CorrelationId.HEADER, "bad id\r\ninjected: header"))
                .andReturn().getResponse().getHeader(CorrelationId.HEADER);

        assertThat(id).matches("[0-9a-f-]{36}");
    }

    @Test
    void errorBodyCarriesTheSameCorrelationId() throws Exception {
        mockMvc.perform(get("/test/api-error").header(CorrelationId.HEADER, "trace-abcdef12"))
                .andExpect(status().isConflict())
                .andExpect(header().string(CorrelationId.HEADER, "trace-abcdef12"))
                .andExpect(jsonPath("$.correlationId").value("trace-abcdef12"));
    }
}
