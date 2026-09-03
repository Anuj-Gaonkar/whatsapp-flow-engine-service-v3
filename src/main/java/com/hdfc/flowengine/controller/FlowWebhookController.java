package com.hdfc.flowengine.controller;

import com.hdfc.flowengine.crypto.FlowDecryptionException;
import com.hdfc.flowengine.crypto.FlowEncryptionService;
import com.hdfc.flowengine.security.WhatsAppSignatureVerifier;
import com.hdfc.flowengine.service.FlowRegistry;
import com.hdfc.flowengine.service.FlowRequestDispatcher;
import java.util.Map;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The WhatsApp Flow data-endpoint - Meta's real, encrypted contract. Registered as a Flow
 * object's own {@code endpoint_uri} in WhatsApp Manager, distinct from the plain messaging
 * webhook. Every screen transition while a Flow is open (INIT, data_exchange, BACK, plus Meta's
 * periodic ping health check) arrives here as RSA-OAEP-wrapped AES key + AES-128-GCM body.
 *
 * <p>Serves both the bare path ({@code POST /webhook/flow}, falling back to
 * {@code flows.default-flow-key}'s entry screen) and any other registered flow ({@code
 * POST /webhook/flow/{flowKey}}) - the per-route default only matters when no flow_session row
 * exists yet (WhatsApp Manager's own Preview/Test button); every real, trigger-originated session
 * resolves its screen purely from the flow_session row's own current_node_id.
 *
 * <p>Per Meta's contract: this always returns HTTP 200 with an encrypted body, except for
 * signature verification failure or decryption failure - those are the only two cases that
 * surface as a non-200 HTTP status. Any error once decryption succeeds is encoded inside the
 * encrypted response payload instead.
 */
@RestController
@RequestMapping("/webhook/flow")
@RequiredArgsConstructor
@Slf4j
public class FlowWebhookController {

	// Per Meta's Flow encryption spec - deliberately unusual codes so Meta's client can tell
	// "reject this request" apart from "this business logic failed".
	private static final int SIGNATURE_INVALID_STATUS = 432;
	private static final int DECRYPTION_FAILED_STATUS = 421;

	private final WhatsAppSignatureVerifier signatureVerifier;
	private final FlowEncryptionService encryptionService;
	private final FlowRequestDispatcher dispatcher;
	private final FlowRegistry flowRegistry;
	private final ObjectMapper objectMapper;

	@PostMapping
	public ResponseEntity<String> handle(
			@RequestBody String rawBody,
			@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
		return process(rawBody, signature, flowRegistry.entryScreenId(flowRegistry.defaultFlowKey()));
	}

	@PostMapping("/{flowKey}")
	public ResponseEntity<String> handleByKey(
			@PathVariable String flowKey,
			@RequestBody String rawBody,
			@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
		return process(rawBody, signature, flowRegistry.entryScreenId(flowKey));
	}

	private ResponseEntity<String> process(String rawBody, String signature, String entryScreenId) {
		if (!signatureVerifier.isValid(rawBody, signature)) {
			log.warn("Rejecting Flow data-endpoint request: invalid X-Hub-Signature-256");
			return ResponseEntity.status(SIGNATURE_INVALID_STATUS).build();
		}

		FlowEncryptionService.DecryptedRequest decrypted;
		try {
			JsonNode envelope = objectMapper.readTree(rawBody);
			decrypted = encryptionService.decrypt(
					envelope.path("encrypted_flow_data").asText(),
					envelope.path("encrypted_aes_key").asText(),
					envelope.path("initial_vector").asText());
		} catch (FlowDecryptionException e) {
			log.error("Failed to decrypt Flow data-endpoint request", e);
			return ResponseEntity.status(DECRYPTION_FAILED_STATUS).build();
		}

		JsonNode body = decrypted.body();
		SecretKey aesKey = decrypted.aesKey();
		byte[] originalIv = decrypted.originalIv();
		String action = body.path("action").asText(null);
		String flowToken = body.path("flow_token").asText(null);
		log.info("Flow data-endpoint request (decrypted): action={} flowToken={} body={}", action, flowToken, body);

		Map<String, Object> responsePayload;
		try {
			responsePayload = dispatcher.dispatch(body, entryScreenId);
		} catch (Exception e) {
			// Per Meta's contract: a processing error is encoded INSIDE the encrypted payload,
			// never as an HTTP error status.
			log.error("Error handling Flow action={} flowToken={}", action, flowToken, e);
			responsePayload = Map.of("data", Map.of("error_message", "Something went wrong. Please try again."));
		}
		log.info("Flow data-endpoint response (before encryption): action={} flowToken={} payload={}",
				action, flowToken, responsePayload);

		String encryptedResponse = encryptionService.encrypt(responsePayload, aesKey, originalIv);
		return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(encryptedResponse);
	}
}
