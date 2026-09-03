package com.hdfc.flowengine.crypto;

import com.hdfc.flowengine.config.WhatsAppProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Meta's WhatsApp Flow data-endpoint encryption contract: RSA-OAEP-SHA256 unwraps a per-request
 * AES-128 key, AES-128-GCM encrypts/decrypts the payload itself, and a response is re-encrypted
 * with the same AES key but a bit-flipped IV. Standard javax.crypto/java.security primitives
 * only - no external crypto library needed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FlowEncryptionService {

	private static final int GCM_TAG_LENGTH_BITS = 128;
	private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";

	// The named transformation "RSA/ECB/OAEPWithSHA-256AndMGF1Padding" only sets the main OAEP
	// digest to SHA-256 and silently leaves MGF1 at the JDK default of SHA-1 unless an explicit
	// OAEPParameterSpec names it too. Meta's reference implementation sets both to SHA-256, so
	// the explicit spec below is required.
	private static final String RSA_TRANSFORMATION = "RSA/ECB/OAEPPadding";
	private static final OAEPParameterSpec RSA_OAEP_PARAMETER_SPEC =
			new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

	private final WhatsAppProperties properties;
	private final ObjectMapper objectMapper;

	private volatile PrivateKey cachedPrivateKey;

	public record DecryptedRequest(JsonNode body, SecretKey aesKey, byte[] originalIv) {
	}

	public DecryptedRequest decrypt(String encryptedFlowDataB64, String encryptedAesKeyB64, String initialVectorB64) {
		try {
			byte[] aesKeyBytes = unwrapAesKey(Base64.getDecoder().decode(encryptedAesKeyB64));
			SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
			byte[] iv = Base64.getDecoder().decode(initialVectorB64);
			byte[] flowData = Base64.getDecoder().decode(encryptedFlowDataB64);

			Cipher aesCipher = Cipher.getInstance(AES_TRANSFORMATION);
			aesCipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
			byte[] plaintext = aesCipher.doFinal(flowData);

			return new DecryptedRequest(objectMapper.readTree(plaintext), aesKey, iv);
		} catch (Exception e) {
			throw new FlowDecryptionException("Failed to decrypt Flow data-endpoint request", e);
		}
	}

	public String encrypt(Map<String, Object> responsePayload, SecretKey aesKey, byte[] originalIv) {
		try {
			byte[] flippedIv = flipBits(originalIv);
			Cipher aesCipher = Cipher.getInstance(AES_TRANSFORMATION);
			aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, flippedIv));
			byte[] ciphertext = aesCipher.doFinal(objectMapper.writeValueAsBytes(responsePayload));
			return Base64.getEncoder().encodeToString(ciphertext);
		} catch (Exception e) {
			throw new FlowDecryptionException("Failed to encrypt Flow data-endpoint response", e);
		}
	}

	private byte[] unwrapAesKey(byte[] encryptedAesKey) throws GeneralSecurityException {
		PrivateKey privateKey = loadPrivateKey();
		Cipher rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION);
		rsaCipher.init(Cipher.DECRYPT_MODE, privateKey, RSA_OAEP_PARAMETER_SPEC);
		return rsaCipher.doFinal(encryptedAesKey);
	}

	private byte[] flipBits(byte[] iv) {
		byte[] flipped = new byte[iv.length];
		for (int i = 0; i < iv.length; i++) {
			flipped[i] = (byte) ~iv[i];
		}
		return flipped;
	}

	private PrivateKey loadPrivateKey() throws GeneralSecurityException {
		PrivateKey key = cachedPrivateKey;
		if (key != null) {
			return key;
		}
		synchronized (this) {
			if (cachedPrivateKey == null) {
				cachedPrivateKey = readPrivateKeyFromDisk();
			}
			return cachedPrivateKey;
		}
	}

	private PrivateKey readPrivateKeyFromDisk() throws GeneralSecurityException {
		try {
			String pem = Files.readString(Path.of(properties.rsaPrivateKeyPath()));
			boolean encrypted = pem.contains("ENCRYPTED PRIVATE KEY");
			String base64 = pem
					.replace("-----BEGIN ENCRYPTED PRIVATE KEY-----", "")
					.replace("-----END ENCRYPTED PRIVATE KEY-----", "")
					.replace("-----BEGIN PRIVATE KEY-----", "")
					.replace("-----END PRIVATE KEY-----", "")
					.replaceAll("\\s", "");
			byte[] keyBytes = Base64.getDecoder().decode(base64);
			KeyFactory keyFactory = KeyFactory.getInstance("RSA");

			if (!encrypted) {
				return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
			}

			String passphrase = properties.rsaPrivateKeyPassphrase();
			if (passphrase == null || passphrase.isBlank()) {
				throw new IllegalStateException("Private key at " + properties.rsaPrivateKeyPath()
						+ " is passphrase-encrypted (-----BEGIN ENCRYPTED PRIVATE KEY-----) but "
						+ "whatsapp.rsa-private-key-passphrase is not set");
			}
			EncryptedPrivateKeyInfo encryptedInfo = new EncryptedPrivateKeyInfo(keyBytes);
			Cipher pbeCipher = Cipher.getInstance(encryptedInfo.getAlgName());
			SecretKeyFactory pbeKeyFactory = SecretKeyFactory.getInstance(encryptedInfo.getAlgName());
			SecretKey pbeKey = pbeKeyFactory.generateSecret(new PBEKeySpec(passphrase.toCharArray()));
			pbeCipher.init(Cipher.DECRYPT_MODE, pbeKey, encryptedInfo.getAlgParameters());
			return keyFactory.generatePrivate(encryptedInfo.getKeySpec(pbeCipher));
		} catch (IOException e) {
			throw new IllegalStateException(
					"Unable to read RSA private key (PKCS8 PEM) from " + properties.rsaPrivateKeyPath(), e);
		}
	}
}
