package org.myorg.dispatchpay.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RequestHasherTest {

    private final RequestHasher hasher = new RequestHasher(JsonMapper.builder().build());

    @Test
    void mapKeyOrderDoesNotChangeHash() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("a", 1);
        first.put("b", "x");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("b", "x");
        second.put("a", 1);

        assertThat(hasher.hash("op", first)).isEqualTo(hasher.hash("op", second)).hasSize(64);
    }

    @Test
    void contentAndOperationBothAffectHash() {
        assertThat(hasher.hash("op", Map.of("a", 1))).isNotEqualTo(hasher.hash("op", Map.of("a", 2)));
        assertThat(hasher.hash("op1", Map.of("a", 1))).isNotEqualTo(hasher.hash("op2", Map.of("a", 1)));
    }
}
