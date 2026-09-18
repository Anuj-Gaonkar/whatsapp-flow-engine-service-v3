package com.hdfc.flowengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds the {@code temporal-workflow-service.*} block - where to POST /reminders to schedule one. */
@ConfigurationProperties(prefix = "temporal-workflow-service")
public record TemporalReminderProperties(String baseUrl) {
}
