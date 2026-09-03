package com.hdfc.flowengine.controller;

import com.hdfc.flowengine.config.WhatsAppProperties;
import com.hdfc.flowengine.security.WhatsAppSignatureVerifier;
import com.hdfc.flowengine.service.FlowEngineService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The regular Cloud API messaging webhook - NOT the Flow data-endpoint (see
 * FlowWebhookController). Meta calls this with plain JSON, signed (not encrypted) via
 * X-Hub-Signature-256: the terminal screen's "complete" action arrives here as an ordinary
 * inbound message with interactive.type == "nfm_reply". Body is read as a raw String so the
 * signature can be verified against the exact bytes Meta signed, before Jackson touches any of
 * it.
 */
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
@Slf4j
public class MessagingWebhookController {

	private final WhatsAppProperties properties;
	private final WhatsAppSignatureVerifier signatureVerifier;
	private final FlowEngineService flowEngineService;
	private final ObjectMapper objectMapper;

	@GetMapping
	public ResponseEntity<String> verify(
			@RequestParam("hub.mode") String mode,
			@RequestParam("hub.verify_token") String verifyToken,
			@RequestParam("hub.challenge") String challenge) {
		if ("subscribe".equals(mode) && properties.verifyToken().equals(verifyToken)) {
			return ResponseEntity.ok(challenge);
		}
		log.warn("Webhook verification failed: mode={} verifyToken={}", mode, verifyToken);
		return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
	}

	@PostMapping
	public ResponseEntity<Void> receive(
			@RequestBody String rawBody,
			@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {

		if (!signatureVerifier.isValid(rawBody, signature)) {
			log.warn("Rejecting messaging webhook: invalid X-Hub-Signature-256");
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		log.info("Messaging webhook request: {}", rawBody);

		JsonNode payload = objectMapper.readTree(rawBody);
		for (JsonNode entry : payload.path("entry")) {
			for (JsonNode change : entry.path("changes")) {
				for (JsonNode message : change.path("value").path("messages")) {
					handleMessage(message);
				}
			}
		}
		return ResponseEntity.ok().build();
	}

	private void handleMessage(JsonNode message) {
		JsonNode interactive = message.path("interactive");
		if (!"nfm_reply".equals(interactive.path("type").asText(null))) {
			return;
		}

		JsonNode nfmReply = interactive.path("nfm_reply");
		String responseJsonText = nfmReply.path("response_json").asText(null);
		if (responseJsonText == null) {
			log.warn("nfm_reply present but response_json missing, from={}", message.path("from").asText(null));
			return;
		}

		try {
			JsonNode responseJson = objectMapper.readTree(responseJsonText);
			String flowToken = responseJson.path("flow_token").asText(null);
			String from = message.path("from").asText(null);
			log.info("Flow completed: from={} flow_token={} response={}", from, flowToken, responseJson);

			Map<String, Object> rawPayload = objectMapper.convertValue(responseJson, Map.class);
			flowEngineService.completeSession(flowToken, from, rawPayload);
		} catch (Exception e) {
			log.error("Failed to parse nfm_reply response_json: {}", responseJsonText, e);
		}
	}
}
