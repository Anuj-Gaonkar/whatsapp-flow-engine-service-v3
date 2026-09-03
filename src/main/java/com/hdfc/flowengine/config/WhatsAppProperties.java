package com.hdfc.flowengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "whatsapp")
public record WhatsAppProperties(
		String cloudApiBaseUrl,
		String wabaId,
		String phoneNumberId,
		String accessToken,
		String verifyToken,
		String appSecret,
		String rsaPrivateKeyPath,
		String rsaPrivateKeyPassphrase
) {
}
