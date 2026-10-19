package com.muslimafree.dashboard.member.photo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.muslimafree.dashboard.common.config.AppProperties;
import com.muslimafree.dashboard.member.CardPhoto;

/**
 * CloudFront signed URLs (canned policy) for photos in the private S3 bucket - the same links user-profile-managment
 * issues with {@code PHOTO_STORAGE=s3}, signed with the same key pair.
 */
@Component
@ConditionalOnProperty(name = "app.photos.storage", havingValue = "s3")
public class CloudFrontPhotoUrlSigner implements PhotoUrlSigner {

    /** CloudFront verifies canned policies with SHA-1; this is fixed by AWS. */
    private static final String SIGNATURE_ALGORITHM = "SHA1withRSA";
    private static final long EXPIRY_STEP_SECONDS = Duration.ofMinutes(10).toSeconds();

    private final String baseUrl;
    private final String keyPairId;
    private final PrivateKey privateKey;
    private final Duration ttl;
    private final Clock clock;

    public CloudFrontPhotoUrlSigner(AppProperties properties, ResourceLoader resourceLoader, Clock clock) {
        AppProperties.Cloudfront cloudfront = properties.photos().cloudfront();
        if (cloudfront == null || !StringUtils.hasText(cloudfront.domain()) || !StringUtils.hasText(cloudfront.keyPairId())
                || !StringUtils.hasText(cloudfront.privateKey())) {
            throw new IllegalStateException("app.photos.cloudfront.domain, key-pair-id and private-key must be set "
                    + "when app.photos.storage=s3");
        }
        this.baseUrl = normalizeDomain(cloudfront.domain());
        this.keyPairId = cloudfront.keyPairId();
        this.privateKey = readPrivateKey(resourceLoader, cloudfront.privateKey());
        this.ttl = properties.photos().urlTtl();
        this.clock = clock;
    }

    @Override
    public String signedUrl(CardPhoto photo) {
        long earliest = clock.instant().plus(ttl).getEpochSecond();
        long expires = (earliest / EXPIRY_STEP_SECONDS + 1) * EXPIRY_STEP_SECONDS;
        String resource = baseUrl + "/" + photo.storageKey();
        return resource + "?Expires=" + expires + "&Signature=" + sign(cannedPolicy(resource, expires))
                + "&Key-Pair-Id=" + keyPairId;
    }

    /** Exactly the document CloudFront rebuilds from the query string - no whitespace, no reordering. */
    private static String cannedPolicy(String resource, long expires) {
        return "{\"Statement\":[{\"Resource\":\"" + resource
                + "\",\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":" + expires + "}}}]}";
    }

    private String sign(String policy) {
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(policy.getBytes(StandardCharsets.UTF_8));
            // CloudFront's own URL-safe alphabet, which is not RFC 4648's
            return Base64.getEncoder().encodeToString(signature.sign())
                    .replace('+', '-').replace('=', '_').replace('/', '~');
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign CloudFront URL", e);
        }
    }

    private static String normalizeDomain(String domain) {
        String normalized = domain.strip();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static PrivateKey readPrivateKey(ResourceLoader resourceLoader, String location) {
        try (InputStream in = resourceLoader.getResource(location).getInputStream()) {
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII)
                    .replaceAll("-----(BEGIN|END) [A-Z ]+-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (GeneralSecurityException | IOException e) {
            // the exception must not carry key material
            throw new IllegalStateException("Cannot load the CloudFront private key from " + location, e);
        }
    }
}
