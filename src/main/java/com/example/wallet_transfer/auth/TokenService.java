package com.example.wallet_transfer.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * Mints and verifies bearer tokens of the form:  base64url(userId) + "." + base64url(HMAC-SHA256(userId))
 *
 * The HMAC is keyed by a server-side secret, so a client cannot forge a token for a
 * user id it doesn't have — it would need the secret to produce a valid signature.
 * This gives genuine "per-user" auth without a user table or Spring Security.
 *
 * NOTE: this is a deliberately minimal scheme for the exercise. A production system
 * would use JWT with expiry + rotation; here tokens are long-lived and unexpiring,
 * which is called out in the write-up as an accepted trade-off.
 */
@Service
public class TokenService {

    private final byte[] secret;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    public TokenService(@Value("${auth.token-secret:dev-secret-change-me}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Issue a token for a user id. */
    public String mint(String userId) {
        String payload = B64.encodeToString(userId.getBytes(StandardCharsets.UTF_8));
        return payload + "." + sign(userId);
    }

    /** Verify a token and, if valid, return the user id it authenticates. */
    public Optional<String> verify(String token) {
        if (token == null) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0) return Optional.empty();
        String payload = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        String userId;
        try {
            userId = new String(B64D.decode(payload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        // constant-time comparison of the signature to avoid timing attacks
        if (!MessageDigest.isEqual(sign(userId).getBytes(StandardCharsets.UTF_8),
                                   sig.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        return Optional.of(userId);
    }

    private String sign(String userId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] h = mac.doFinal(userId.getBytes(StandardCharsets.UTF_8));
            return B64.encodeToString(h);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign token", e);
        }
    }
}
