package com.hdfc.flowengine.service;

import com.hdfc.flowengine.model.ScreenResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns one decoded Flow data-endpoint request body (action/flow_token/screen/data) into a
 * response payload, shared by both the encrypted (/webhook/flow) and plain (/screen) controllers
 * - the two only differ in how the body arrives and how the response is wrapped, never in what
 * action means what.
 */
@Component
@RequiredArgsConstructor
public class FlowRequestDispatcher {

	private final FlowEngineService flowEngineService;
	private final ObjectMapper objectMapper;

	public Map<String, Object> dispatch(JsonNode body, String defaultEntryScreenId) {
		String action = body.path("action").asText(null);
		String flowToken = body.path("flow_token").asText(null);

		return switch (action) {
			case "ping" -> Map.of("data", flowEngineService.ping());
			case "INIT" -> toResponsePayload(flowEngineService.init(flowToken,
					body.path("data").path("wa_id").asText(null), entryScreenIdOrDefault(body, defaultEntryScreenId)));
			case "data_exchange" -> toResponsePayload(flowEngineService.dataExchange(
					flowToken, body.path("screen").asText(null), toMap(body.path("data"))));
			case "BACK" -> toResponsePayload(flowEngineService.back(flowToken, body.path("screen").asText(null)));
			default -> throw new IllegalArgumentException("Unsupported Flow action " + action);
		};
	}

	private String entryScreenIdOrDefault(JsonNode body, String defaultEntryScreenId) {
		String screenId = body.path("screen").asText(null);
		return (screenId == null || screenId.isBlank()) ? defaultEntryScreenId : screenId;
	}

	private Map<String, Object> toResponsePayload(ScreenResponse response) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("screen", response.screen());
		payload.put("data", response.data());
		return payload;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> toMap(JsonNode dataNode) {
		if (dataNode == null || dataNode.isMissingNode() || dataNode.isNull()) {
			return Map.of();
		}
		return objectMapper.convertValue(dataNode, Map.class);
	}
}
