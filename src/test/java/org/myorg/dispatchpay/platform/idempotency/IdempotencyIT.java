package org.myorg.dispatchpay.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.myorg.dispatchpay.IntegrationTest;
import org.myorg.dispatchpay.support.WidgetService;
import org.myorg.dispatchpay.support.WidgetService.CreateWidget;
import org.myorg.dispatchpay.support.WidgetService.WidgetCreated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** M00.S02 gate: same key/content returns the original result; changed content conflicts. */
class IdempotencyIT extends IntegrationTest {

    private static final String KEY_HEADER = IdempotentCommandExecutor.HEADER;

    @Autowired
    IdempotentCommandExecutor executor;

    @Test
    void sameKeyAndContentReplaysOriginalResponseWithoutReExecuting() throws Exception {
        String first = createWidget("key-00000001", "{\"name\":\"bolt\",\"quantity\":5}")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotentResult.REPLAYED_HEADER))
                .andReturn().getResponse().getContentAsString();

        String second = createWidget("key-00000001", "{\"quantity\":5,\"name\":\"bolt\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string(IdempotentResult.REPLAYED_HEADER, "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(widgetService.executions()).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentContentConflicts() throws Exception {
        createWidget("key-00000002", "{\"name\":\"bolt\",\"quantity\":5}").andExpect(status().isCreated());

        createWidget("key-00000002", "{\"name\":\"bolt\",\"quantity\":6}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(widgetService.executions()).isEqualTo(1);
    }

    @Test
    void differentKeysExecuteIndependently() throws Exception {
        createWidget("key-00000003", "{\"name\":\"bolt\",\"quantity\":5}").andExpect(status().isCreated());
        createWidget("key-00000004", "{\"name\":\"bolt\",\"quantity\":5}").andExpect(status().isCreated());

        assertThat(widgetService.executions()).isEqualTo(2);
    }

    @Test
    void missingOrMalformedKeyIsRejected() throws Exception {
        mockMvc.perform(post("/test/widgets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bolt\",\"quantity\":5}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        createWidget("short", "{\"name\":\"bolt\",\"quantity\":5}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));

        assertThat(widgetService.executions()).isZero();
    }

    @Test
    void failedCommandRollsBackKeyAndEffectsSoRetryRunsAgain() throws Exception {
        createWidget("key-00000005", "{\"name\":\"fail\",\"quantity\":5}").andExpect(status().isUnprocessableContent());
        assertThat(count("idempotency_record")).isZero();
        assertThat(count("outbox_event")).isZero();

        createWidget("key-00000005", "{\"name\":\"fail\",\"quantity\":5}").andExpect(status().isUnprocessableContent());
        assertThat(widgetService.executions()).isEqualTo(2);
    }

    @Test
    void expiredKeyExecutesAgain() throws Exception {
        createWidget("key-00000006", "{\"name\":\"bolt\",\"quantity\":5}").andExpect(status().isCreated());
        jdbc.sql("UPDATE idempotency_record SET expires_at = now() - interval '1 second'").update();

        createWidget("key-00000006", "{\"name\":\"bolt\",\"quantity\":5}")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotentResult.REPLAYED_HEADER));

        assertThat(widgetService.executions()).isEqualTo(2);
        assertThat(count("idempotency_record")).isEqualTo(1);
    }

    @Test
    void concurrentDuplicatesExecuteOnceAndAllReceiveTheSameResult() throws Exception {
        widgetService.delay(Duration.ofMillis(300));
        CreateWidget command = new CreateWidget("nut", 3);
        IdempotentRequest request = new IdempotentRequest("test-scope", "widgets.create", "key-concurrent", command);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IdempotentResult<WidgetCreated>>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return executor.execute(request, WidgetCreated.class, () -> widgetService.create(command));
                }));
            }
            start.countDown();

            List<IdempotentResult<WidgetCreated>> results = new ArrayList<>();
            for (Future<IdempotentResult<WidgetCreated>> future : futures) {
                results.add(future.get());
            }

            assertThat(widgetService.executions()).isEqualTo(1);
            assertThat(results).extracting(IdempotentResult::body).containsOnly(results.getFirst().body());
            assertThat(results).filteredOn(IdempotentResult::replayed).hasSize(threads - 1);
            assertThat(count("outbox_event")).isEqualTo(1);
        }
    }

    private ResultActions createWidget(String key, String json) throws Exception {
        return mockMvc.perform(post("/test/widgets")
                .header(KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }
}
