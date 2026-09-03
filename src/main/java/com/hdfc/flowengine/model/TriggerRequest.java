package com.hdfc.flowengine.model;

/** POST /trigger body. flowKey is optional - defaults to flows.default-flow-key. */
public record TriggerRequest(String to, String flowKey) {
}
