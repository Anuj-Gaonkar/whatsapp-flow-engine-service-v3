package com.hdfc.flowengine.service;

import com.hdfc.flowengine.config.TemporalReminderProperties;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** Thin wrapper around temporal-workflow-service's POST /reminders, used to schedule a reminder. */
@Service
@Slf4j
public class TemporalReminderClient {

	private final RestClient restClient;

	public TemporalReminderClient(TemporalReminderProperties properties) {
		this.restClient = RestClient.builder()
				.baseUrl(properties.baseUrl())
				.build();
	}

	public ScheduleResult schedule(String waId, String message, Instant remindAt) {
		log.info("Scheduling reminder waId={} remindAt={}", waId, remindAt);
		ScheduleResult result = restClient.post()
				.uri("/reminders")
				.contentType(MediaType.APPLICATION_JSON)
				.body(new ScheduleRequest(waId, message, remindAt))
				.retrieve()
				.body(ScheduleResult.class);
		log.info("Scheduled reminder waId={} result={}", waId, result);
		return result;
	}

	private record ScheduleRequest(String waId, String message, Instant remindAt) {
	}

	public record ScheduleResult(String reminderId, String workflowId, Instant remindAt) {
	}
}
