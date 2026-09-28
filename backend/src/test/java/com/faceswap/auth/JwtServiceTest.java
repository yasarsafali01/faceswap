package com.faceswap.auth;

import com.faceswap.common.ApiException;
import com.faceswap.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private final JwtService jwt = new JwtService(new AppProperties(
            new AppProperties.Jwt("0123456789abcdef0123456789abcdef-test", Duration.ofMinutes(5),
                    Duration.ofDays(1), Duration.ofHours(1)),
            null, null, null));

    @Test
    void accessTokenRoundTrip() {
        var token = jwt.accessToken(42, List.of("USER"));
        var claims = jwt.parse(token.token(), JwtService.TokenType.ACCESS);
        assertEquals("42", claims.getSubject());
        assertEquals(token.jti(), claims.getId());
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessToken() {
        var token = jwt.refreshToken(42);
        assertThrows(ApiException.class, () -> jwt.parse(token.token(), JwtService.TokenType.ACCESS));
    }

    @Test
    void tamperedTokenIsRejected() {
        var token = jwt.mediaToken(1, "results/1/x.mp4").token();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        assertThrows(ApiException.class, () -> jwt.parse(tampered, JwtService.TokenType.MEDIA));
    }

    @Test
    void mediaTokenIsStableForSameKey() {
        assertEquals(jwt.mediaToken(1, "faces/1/a.jpg").token(), jwt.mediaToken(1, "faces/1/a.jpg").token());
        var claims = jwt.parse(jwt.mediaToken(1, "faces/1/a.jpg").token(), JwtService.TokenType.MEDIA);
        assertEquals("faces/1/a.jpg", claims.get("key", String.class));
    }

    @Test
    void shortSecretIsRefused() {
        assertThrows(IllegalStateException.class, () -> new JwtService(new AppProperties(
                new AppProperties.Jwt("short", Duration.ofMinutes(5), Duration.ofDays(1), Duration.ofHours(1)),
                null, null, null)));
    }
}
