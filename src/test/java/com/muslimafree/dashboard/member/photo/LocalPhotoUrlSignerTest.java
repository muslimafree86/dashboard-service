package com.muslimafree.dashboard.member.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.muslimafree.dashboard.common.config.AppProperties;
import com.muslimafree.dashboard.member.CardPhoto;

class LocalPhotoUrlSignerTest {

    private static final String KEY = "shared-photo-signing-key-0123456789";
    private static final Instant NOW = Instant.parse("2026-10-06T12:03:17Z");
    private static final Pattern LINK =
            Pattern.compile("/api/v1/photos/([0-9a-f-]{36})/content\\?expires=(\\d+)&signature=([A-Za-z0-9_-]+)");

    private static AppProperties properties(String key) {
        return new AppProperties(new AppProperties.Web(List.of()), null, null, null,
                new AppProperties.Photos("local", Duration.ofHours(1), key, null), null, null, null, null, null);
    }

    private final LocalPhotoUrlSigner signer = new LocalPhotoUrlSigner(properties(KEY), Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * The link must verify in user-profile-managment, which recomputes HMAC-SHA256(key, "{photoId}:{expires}"),
     * base64url without padding, and accepts it until {@code expires}.
     */
    @Test
    void producesLinksTheProfileServiceAccepts() throws Exception {
        UUID photoId = UUID.randomUUID();
        Matcher link = LINK.matcher(signer.signedUrl(new CardPhoto(photoId, UUID.randomUUID(), "users/x.jpg")));

        assertThat(link.matches()).isTrue();
        assertThat(link.group(1)).isEqualTo(photoId.toString());
        long expires = Long.parseLong(link.group(2));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal((photoId + ":" + expires).getBytes(StandardCharsets.US_ASCII)));
        assertThat(link.group(3)).isEqualTo(expected);
    }

    @Test
    void expiryIsAtLeastTheTtlAndStableForCaching() {
        CardPhoto photo = new CardPhoto(UUID.randomUUID(), UUID.randomUUID(), "k");
        long expires = expires(signer.signedUrl(photo));

        assertThat(expires).isGreaterThanOrEqualTo(NOW.plus(Duration.ofHours(1)).getEpochSecond())
                .isLessThanOrEqualTo(NOW.plus(Duration.ofMinutes(70)).getEpochSecond())
                .isEqualTo(expires(new LocalPhotoUrlSigner(properties(KEY),
                        Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC)).signedUrl(photo)));
    }

    @Test
    void refusesToStartWithoutASharedKey() {
        assertThatThrownBy(() -> new LocalPhotoUrlSigner(properties(" "), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PHOTO_URL_SIGNING_KEY");
    }

    private static long expires(String url) {
        Matcher m = LINK.matcher(url);
        assertThat(m.matches()).isTrue();
        return Long.parseLong(m.group(2));
    }
}
