package com.faceswap.common;

import com.faceswap.auth.AuthUser;
import com.faceswap.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Fixed-window rate limiting in Redis. Auth endpoints are limited per client IP,
 * uploads and job creation per authenticated user.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private record Rule(String bucket, int limit, Duration window) {
    }

    private final StringRedisTemplate redis;
    private final AppProperties.RateLimit props;

    public RateLimitFilter(StringRedisTemplate redis, AppProperties properties) {
        this.redis = redis;
        this.props = properties.rateLimit();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Rule rule = ruleFor(request);
        if (rule != null) {
            String subject = rule.bucket().equals("auth") ? request.getRemoteAddr() : currentUserId();
            if (subject != null && !allow(rule, subject)) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", String.valueOf(rule.window().toSeconds()));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"TOO_MANY_REQUESTS\",\"message\":\"Çok fazla istek, lütfen biraz bekleyin\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private Rule ruleFor(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return null;
        }
        String path = request.getRequestURI();
        if (path.equals("/api/auth/login") || path.equals("/api/auth/register")) {
            return new Rule("auth", props.authPerMinute(), Duration.ofMinutes(1));
        }
        if (path.equals("/api/videos/upload") || path.equals("/api/faces/upload")) {
            return new Rule("upload", props.uploadsPerHour(), Duration.ofHours(1));
        }
        if (path.equals("/api/jobs/start")) {
            return new Rule("jobs", props.jobsPerHour(), Duration.ofHours(1));
        }
        return null;
    }

    private boolean allow(Rule rule, String subject) {
        long window = System.currentTimeMillis() / rule.window().toMillis();
        String key = "ratelimit:" + rule.bucket() + ":" + subject + ":" + window;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, rule.window());
        }
        return count == null || count <= rule.limit();
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthUser u ? u.getName() : null;
    }
}
