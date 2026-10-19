package com.muslimafree.dashboard.common.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CoreConfig {

    /** Injected everywhere time matters (ages, "online now", cursor expiry) so tests can control it. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
