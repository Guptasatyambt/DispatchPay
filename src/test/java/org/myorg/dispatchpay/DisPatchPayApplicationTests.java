package org.myorg.dispatchpay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DisPatchPayApplicationTests extends IntegrationTest {

    @Test
    void contextLoadsAndMigrationsApply() {
        Integer applied = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success")
                .query(Integer.class).single();
        assertThat(applied).isPositive();
    }
}
