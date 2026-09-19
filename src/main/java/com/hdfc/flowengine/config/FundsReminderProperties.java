package com.hdfc.flowengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code reminder.funds.*}. DEMO schedules the funds reminder in minutes (3/5/7 for the
 * three timing options, 5 by default) so a demo doesn't wait days; PRODUCTION uses the real
 * day-based delays (3/7/15, 7 by default).
 */
@ConfigurationProperties(prefix = "reminder.funds")
public record FundsReminderProperties(@DefaultValue("DEMO") Mode mode) {

	public enum Mode {
		DEMO, PRODUCTION
	}
}
