package com.hdfc.flowengine.controller;

import com.hdfc.flowengine.config.WhatsAppProperties;
import com.hdfc.flowengine.model.TriggerRequest;
import com.hdfc.flowengine.service.FlowMessageSenderService;
import com.hdfc.flowengine.service.FlowRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

/**
 * POST /trigger - sends any registered flow to a WhatsApp number. Which flow is picked is driven
 * by the request body's flowKey (defaults to flows.default-flow-key when omitted), never by the
 * URL - registering a new flow (see FlowRegistry) never needs a new route here.
 */
@RestController
@RequestMapping
@RequiredArgsConstructor
@Slf4j
public class TriggerController {

	private final FlowMessageSenderService flowMessageSenderService;
	private final WhatsAppProperties whatsAppProperties;
	private final FlowRegistry flowRegistry;

	@PostMapping("/trigger")
	public ResponseEntity<Map<String, Object>> trigger(@RequestBody TriggerRequest request) {
		log.info("to: {}", request.to());
		String flowKey = isBlank(request.flowKey()) ? flowRegistry.defaultFlowKey() : request.flowKey();

		if (!flowRegistry.isRegistered(flowKey)) {
			return ResponseEntity.badRequest().body(Map.of("error", "Unknown flow key: " + flowKey));
		}
		if (request.to() == null || request.to().isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("error", "\"to\" is required (E.164 phone number)"));
		}

		List<String> missing = missingConfig(flowKey);
		if (!missing.isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of(
					"error", "WhatsApp config not set, cannot trigger yet",
					"missing", missing
			));
		}

		try {
			FlowMessageSenderService.SendResult result = flowMessageSenderService.send(flowKey, request.to());
			return ResponseEntity.ok(Map.of(
					"flow_key", flowKey,
					"flow_token", result.flowToken(),
					"meta_response", result.metaResponse()
			));
		} catch (RestClientResponseException ex) {
			log.error("WhatsApp trigger failed: status={} body={}", ex.getStatusCode(), ex.getResponseBodyAsString());
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
					"error", "WhatsApp send failed",
					"status", ex.getStatusCode().value(),
					"body", ex.getResponseBodyAsString()
			));
		}
	}

	private List<String> missingConfig(String flowKey) {
		List<String> missing = new ArrayList<>();
		if (isBlank(whatsAppProperties.phoneNumberId())) missing.add("WA_PHONE_NUMBER_ID");
		if (isBlank(whatsAppProperties.accessToken())) missing.add("WA_ACCESS_TOKEN");
		if (isBlank(flowRegistry.metaFlowId(flowKey))) missing.add("meta-flow-id for flow key " + flowKey);
		return missing;
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
