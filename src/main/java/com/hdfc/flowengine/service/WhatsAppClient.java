package com.hdfc.flowengine.service;

import com.hdfc.flowengine.config.WhatsAppProperties;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Thin wrapper around Meta's Cloud API "send message" endpoint, used to trigger a Flow. A plain
 * authenticated HTTPS POST - no encryption involved (that's only for the data-endpoint, not for
 * sending the trigger message itself).
 */
@Service
@Slf4j
public class WhatsAppClient {

	private final RestClient restClient;
	private final WhatsAppProperties properties;

	public WhatsAppClient(WhatsAppProperties properties) {
		this.properties = properties;
		this.restClient = RestClient.builder()
				.baseUrl(properties.cloudApiBaseUrl())
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.accessToken())
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.build();
	}

	/**
	 * Sends a Flow to {@code to}, entering at {@code entryScreenId} of the Meta Flow object
	 * identified by {@code metaFlowId}. flowToken is embedded so every subsequent data-endpoint
	 * call and the eventual completion webhook can be correlated back to this send.
	 */
	public Map<String, Object> sendFlow(String to, String flowToken, String metaFlowId, String entryScreenId,
			String mode, String ctaLabel, String bodyText) {
		Map<String, Object> body = Map.of(
				"messaging_product", "whatsapp",
				"to", to,
				"type", "interactive",
				"interactive", Map.of(
						"type", "flow",
						"body", Map.of("text", bodyText),
						"action", Map.of(
								"name", "flow",
								"parameters", Map.of(
										"flow_message_version", "3",
										"flow_token", flowToken,
										"flow_id", metaFlowId,
										"flow_cta", ctaLabel,
										"flow_action", "navigate",
										"mode", mode,
										"flow_action_payload", Map.of("screen", entryScreenId, "data", Map.of("wa_id", to))
								)
						)
				)
		);

		log.info("Sending Flow trigger message to={} flowToken={} request={}", to, flowToken, body);
		Map<String, Object> response = restClient.post()
				.uri("/{phoneNumberId}/messages", properties.phoneNumberId())
				.body(body)
				.retrieve()
				.body(new ParameterizedTypeReference<Map<String, Object>>() {
				});
		log.info("Flow trigger message response to={} flowToken={} response={}", to, flowToken, response);
		return response;
	}
}
