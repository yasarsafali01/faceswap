package com.faceswap.auth;

import com.faceswap.common.ApiException;
import com.faceswap.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class JwtService {

    public enum TokenType {ACCESS, REFRESH, MEDIA}

    public record IssuedToken(String token, String jti, Instant expiresAt) {
    }

    private static final String TYPE_CLAIM = "typ";

    private final SecretKey key;
    private final AppProperties.Jwt props;

    public JwtService(AppProperties properties) {
        this.props = properties.jwt();
        byte[] secret = props.secret() == null ? new byte[0] : props.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public IssuedToken accessToken(long userId, List<String> roles) {
        return issue(TokenType.ACCESS, String.valueOf(userId), Instant.now(), props.accessTtl(),
                UUID.randomUUID().toString(), b -> b.claim("roles", roles));
    }

    public IssuedToken refreshToken(long userId) {
        return issue(TokenType.REFRESH, String.valueOf(userId), Instant.now(), props.refreshTtl(),
                UUID.randomUUID().toString(), b -> b);
    }

    /**
     * Signed link for &lt;video&gt;/&lt;img&gt; tags, which cannot send an Authorization header.
     * Deterministic within an hour so repeated job updates yield the same URL and browsers can cache it;
     * every token stays valid for at least mediaTtl.
     */
    public IssuedToken mediaToken(long userId, String objectKey) {
        long hour = Instant.now().getEpochSecond() / 3600 * 3600;
        String jti = UUID.nameUUIDFromBytes((objectKey + "|" + hour).getBytes(StandardCharsets.UTF_8)).toString();
        return issue(TokenType.MEDIA, String.valueOf(userId), Instant.ofEpochSecond(hour),
                props.mediaTtl().plusHours(1), jti, b -> b.claim("key", objectKey));
    }

    public Claims parse(String token, TokenType expected) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!expected.name().equals(claims.get(TYPE_CLAIM, String.class))) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Geçersiz token türü");
            }
            return claims;
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Geçersiz veya süresi dolmuş token");
        }
    }

    private IssuedToken issue(TokenType type, String subject, Instant now, Duration ttl, String jti,
                              java.util.function.UnaryOperator<io.jsonwebtoken.JwtBuilder> extra) {
        Instant exp = now.plus(ttl);
        var builder = Jwts.builder()
                .id(jti)
                .subject(subject)
                .claim(TYPE_CLAIM, type.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp));
        String token = extra.apply(builder).signWith(key).compact();
        return new IssuedToken(token, jti, exp);
    }
}
