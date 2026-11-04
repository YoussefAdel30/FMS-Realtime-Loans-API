package com.company.msisdn.config;

import java.time.Clock;
import java.util.TimeZone;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InfrastructureConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer utcTimestamps() {
        return builder -> builder.timeZone(TimeZone.getTimeZone("UTC"));
    }
}
