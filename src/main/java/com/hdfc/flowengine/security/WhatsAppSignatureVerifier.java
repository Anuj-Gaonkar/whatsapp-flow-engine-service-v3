package com.hdfc.flowengine.security;

import com.hdfc.flowengine.config.WhatsAppProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Verifies Meta's {@code X-Hub-Signature-256} header - HMAC-SHA256 over the raw request body,
 * keyed on the app secret. Present on every request to a registered endpoint URI: the plain
 * messaging webhook and the encrypted Flow data-endpoint both carry it. Must run against the raw
 * body bytes, before Jackson deserializes anything.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WhatsAppSignatureVerifier {

	private static final String ALGORITHM = "HmacSHA256";
	private static final String PREFIX = "sha256=";

	private final WhatsAppProperties properties;

	public boolean isValid(String rawBody, String signatureHeader) {
		if (signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
			log.warn("Missing or malformed X-Hub-Signature-256 header");
			return false;
		}
		if (properties.appSecret() == null || properties.appSecret().isBlank()) {
			log.warn("whatsapp.app-secret is not configured; refusing to accept an unverifiable request");
			return false;
		}

		String expectedHex = computeHmacHex(rawBody);
		String providedHex = signatureHeader.substring(PREFIX.length());

		return MessageDigest.isEqual(
				expectedHex.getBytes(StandardCharsets.UTF_8),
				providedHex.getBytes(StandardCharsets.UTF_8));
	}

	private String computeHmacHex(String rawBody) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(properties.appSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
			byte[] digest = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException("Unable to compute HMAC-SHA256 signature", e);
		}
	}
}
