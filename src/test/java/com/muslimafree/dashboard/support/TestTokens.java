package com.muslimafree.dashboard.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Plays user-profile-managment's token issuer: an RS256 key pair whose public half is handed to the dashboard as a PEM
 * file (JWT_PUBLIC_KEY_LOCATION), and access tokens with the same claims ({@code sub}, {@code sid}, {@code roles}).
 */
public final class TestTokens {

    public static final String ISSUER = "https://auth.muslimafree.test";

    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER_KEYS = generate();
    private static final Path PUBLIC_KEY_FILE = writePublicKey();

    private TestTokens() {
    }

    public static String publicKeyLocation() {
        return PUBLIC_KEY_FILE.toUri().toString();
    }

    public static String accessToken(UUID userId, UUID sessionId) {
        return sign(KEYS, ISSUER, userId, sessionId, List.of("MEMBER"), Instant.now().plus(Duration.ofMinutes(15)));
    }

    public static String withIssuer(String issuer, UUID userId, UUID sessionId) {
        return sign(KEYS, issuer, userId, sessionId, List.of("MEMBER"), Instant.now().plus(Duration.ofMinutes(15)));
    }

    public static String signedByAnotherKey(UUID userId, UUID sessionId) {
        return sign(OTHER_KEYS, ISSUER, userId, sessionId, List.of("MEMBER"), Instant.now().plus(Duration.ofMinutes(15)));
    }

    public static String expired(UUID userId, UUID sessionId) {
        return sign(KEYS, ISSUER, userId, sessionId, List.of("MEMBER"), Instant.now().minus(Duration.ofMinutes(5)));
    }

    public static String withRoles(UUID userId, UUID sessionId, List<String> roles) {
        return sign(KEYS, ISSUER, userId, sessionId, roles, Instant.now().plus(Duration.ofMinutes(15)));
    }

    public static String withoutRoles(UUID userId, UUID sessionId) {
        return sign(KEYS, ISSUER, userId, sessionId, List.of(), Instant.now().plus(Duration.ofMinutes(15)));
    }

    private static String sign(KeyPair keys, String issuer, UUID userId, UUID sessionId, List<String> roles,
            Instant expiresAt) {
        try {
            Instant issuedAt = expiresAt.minus(Duration.ofMinutes(15));
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(userId.toString())
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .claim("sid", sessionId.toString())
                    .claim("roles", roles)
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writePublicKey() {
        try {
            Path file = Files.createTempFile("jwt-public", ".pem");
            String pem = "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(((RSAPublicKey) KEYS.getPublic()).getEncoded())
                    + "\n-----END PUBLIC KEY-----\n";
            Files.writeString(file, pem);
            file.toFile().deleteOnExit();
            return file;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
