package org.myorg.dispatchpay.platform.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
public class PlatformConfig {

    /** All instants are UTC; inject this instead of calling Instant.now() so time is testable. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Background pollers (outbox, cleanup). Tests disable this and drive the workers directly. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "dispatchpay.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
