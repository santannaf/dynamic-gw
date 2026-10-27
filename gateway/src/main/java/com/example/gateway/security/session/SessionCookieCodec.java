package com.example.gateway.security.session;

import com.example.gateway.security.GatewaySecurityProperties;
import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.text.ParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Encrypts small JSON payloads into compact JWE strings ({@code dir} +
 * {@code A256GCM}) for the gateway's cookies. The first configured key
 * encrypts; every key decrypts, picked by the {@code kid} header, so keys
 * rotate without logging users out. Anything that fails to decrypt or parse
 * is reported as absent: a tampered cookie is just "no session".
 */
public class SessionCookieCodec {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String currentKeyId;
    private final Map<String, SecretKey> keys;

    public SessionCookieCodec(List<GatewaySecurityProperties.Session.Key> configured) {
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException("gateway.security.session.keys must contain at least one key");
        }
        Map<String, SecretKey> parsed = new LinkedHashMap<>();
        for (GatewaySecurityProperties.Session.Key key : configured) {
            if (key.getId() == null || key.getId().isBlank()) {
                throw new IllegalStateException("gateway.security.session.keys[].id is required");
            }
            byte[] secret;
            try {
                secret = Base64.getDecoder().decode(key.getSecret() == null ? "" : key.getSecret().trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("gateway.security.session.keys[" + key.getId() + "].secret is not valid base64", e);
            }
            if (secret.length != 32) {
                throw new IllegalStateException("gateway.security.session.keys[" + key.getId()
                        + "].secret must decode to 32 bytes (A256GCM), got " + secret.length);
            }
            parsed.put(key.getId(), new SecretKeySpec(secret, "AES"));
        }
        this.keys = Map.copyOf(parsed);
        this.currentKeyId = configured.getFirst().getId();
    }

    public String encode(ObjectNode payload) {
        JWEHeader header = new JWEHeader.Builder(JWEAlgorithm.DIR, EncryptionMethod.A256GCM)
                .keyID(currentKeyId)
                .build();
        JWEObject jwe = new JWEObject(header, new Payload(JSON.writeValueAsString(payload)));
        try {
            jwe.encrypt(new DirectEncrypter(keys.get(currentKeyId)));
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to encrypt session cookie", e);
        }
        return jwe.serialize();
    }

    public Optional<ObjectNode> decode(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            JWEObject jwe = JWEObject.parse(value);
            if (!JWEAlgorithm.DIR.equals(jwe.getHeader().getAlgorithm())
                    || !EncryptionMethod.A256GCM.equals(jwe.getHeader().getEncryptionMethod())) {
                return Optional.empty();
            }
            SecretKey key = keys.get(jwe.getHeader().getKeyID());
            if (key == null) {
                return Optional.empty();
            }
            jwe.decrypt(new DirectDecrypter(key));
            JsonNode node = JSON.readTree(jwe.getPayload().toString());
            return node instanceof ObjectNode object ? Optional.of(object) : Optional.empty();
        } catch (ParseException | JOSEException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public boolean isCurrentKey(String value) {
        try {
            return currentKeyId.equals(JWEObject.parse(value).getHeader().getKeyID());
        } catch (ParseException e) {
            return false;
        }
    }

    public static ObjectNode newPayload() {
        return JSON.createObjectNode();
    }
}
