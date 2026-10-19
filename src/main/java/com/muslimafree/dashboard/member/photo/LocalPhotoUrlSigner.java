package com.muslimafree.dashboard.member.photo;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.muslimafree.dashboard.common.config.AppProperties;
import com.muslimafree.dashboard.member.CardPhoto;

/**
 * HMAC-SHA256 links to user-profile-managment's local photo storage:
 * {@code /api/v1/photos/{id}/content?expires=..&signature=base64url(HMAC(key, "{id}:{expires}"))}. Both services must
 * share {@code PHOTO_URL_SIGNING_KEY}; without it the links would never verify, so startup fails instead.
 */
@Component
@ConditionalOnProperty(name = "app.photos.storage", havingValue = "local", matchIfMissing = true)
public class LocalPhotoUrlSigner implements PhotoUrlSigner {

    private static final String ALGORITHM = "HmacSHA256";
    /**
     * Expiry is rounded up to this step, so a photo keeps the same URL for a while and the browser can cache it across
     * feed pages and refreshes. A link therefore lives between {@code url-ttl} and {@code url-ttl} + this step.
     */
    private static final long EXPIRY_STEP_SECONDS = Duration.ofMinutes(10).toSeconds();

    private final SecretKeySpec key;
    private final Duration ttl;
    private final Clock clock;

    public LocalPhotoUrlSigner(AppProperties properties, Clock clock) {
        String secret = properties.photos().urlSigningKey();
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("PHOTO_URL_SIGNING_KEY must be set to the same value as in "
                    + "user-profile-managment when app.photos.storage=local");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.ttl = properties.photos().urlTtl();
        this.clock = clock;
    }

    @Override
    public String signedUrl(CardPhoto photo) {
        long earliest = clock.instant().plus(ttl).getEpochSecond();
        long expires = (earliest / EXPIRY_STEP_SECONDS + 1) * EXPIRY_STEP_SECONDS;
        return "/api/v1/photos/" + photo.id() + "/content?expires=" + expires + "&signature=" + sign(photo.id(), expires);
    }

    String sign(UUID photoId, long expires) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal((photoId + ":" + expires).getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
