package com.hdfc.flowengine.model;

/** POST /messages/reminder body - called by temporal-workflow-service when a reminder fires. */
public record ReminderMessageRequest(String waId, String message) {
}
