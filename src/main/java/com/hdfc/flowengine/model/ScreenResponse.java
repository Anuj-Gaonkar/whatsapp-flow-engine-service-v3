package com.hdfc.flowengine.model;

import java.util.Map;

/** What every {@code FlowEngineService} navigation method resolves to - {screen, data}. */
public record ScreenResponse(String screen, Map<String, Object> data) {
}
