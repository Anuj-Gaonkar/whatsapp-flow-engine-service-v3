package com.hdfc.flowengine.controller;

import com.hdfc.flowengine.service.FlowRegistry;
import com.hdfc.flowengine.service.FlowRequestDispatcher;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Plain-JSON twin of FlowWebhookController - same INIT/data_exchange/BACK/ping contract and the
 * same FlowRequestDispatcher underneath, but no RSA/AES envelope and no X-Hub-Signature-256
 * check, since nothing here was actually sent by Meta. For local testing and for any client that
 * isn't Meta's own Flow runtime (e.g. a middleware that already deals with Meta's real
 * encryption elsewhere and would rather talk plain JSON to this service).
 */
@RestController
@RequestMapping("/screen")
@RequiredArgsConstructor
@Slf4j
public class ScreenController {

	private final FlowRequestDispatcher dispatcher;
	private final FlowRegistry flowRegistry;
	private final ObjectMapper objectMapper;

	@PostMapping
	public ResponseEntity<String> handle(@RequestBody String rawBody) {
		return process(rawBody, flowRegistry.entryScreenId(flowRegistry.defaultFlowKey()));
	}

	@PostMapping("/{flowKey}")
	public ResponseEntity<String> handleByKey(@PathVariable String flowKey, @RequestBody String rawBody) {
		return process(rawBody, flowRegistry.entryScreenId(flowKey));
	}

	private ResponseEntity<String> process(String rawBody, String entryScreenId) {
		JsonNode body = objectMapper.readTree(rawBody);
		String action = body.path("action").asText(null);
		String flowToken = body.path("flow_token").asText(null);
		log.info("Screen request (plaintext): action={} flowToken={} body={}", action, flowToken, body);

		Map<String, Object> responsePayload;
		try {
			responsePayload = dispatcher.dispatch(body, entryScreenId);
		} catch (Exception e) {
			log.error("Error handling screen action={} flowToken={}", action, flowToken, e);
			responsePayload = Map.of("data", Map.of("error_message", "Something went wrong. Please try again."));
		}
		log.info("Screen response (plaintext): action={} flowToken={} payload={}", action, flowToken, responsePayload);
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(objectMapper.writeValueAsString(responsePayload));
	}
}
