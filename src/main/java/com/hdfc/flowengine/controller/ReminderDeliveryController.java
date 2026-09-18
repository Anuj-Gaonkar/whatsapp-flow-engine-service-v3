package com.hdfc.flowengine.controller;

import com.hdfc.flowengine.model.ReminderMessageRequest;
import com.hdfc.flowengine.service.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by temporal-workflow-service's ReminderDeliveryClient once a scheduled reminder's
 * Temporal timer fires - a direct HTTP call, no Kafka in between. This service owns the WhatsApp
 * Cloud API credentials/client, so delivery happens here.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ReminderDeliveryController {

	private final WhatsAppClient whatsAppClient;

	@PostMapping("/messages/reminder")
	public ResponseEntity<Void> deliverReminder(@RequestBody ReminderMessageRequest request) {
		log.info("Delivering reminder message to={}", request.waId());
		whatsAppClient.sendText(request.waId(), request.message());
		return ResponseEntity.ok().build();
	}
}
