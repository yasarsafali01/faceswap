package com.faceswap.auth;

import com.faceswap.common.ApiException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenStore tokenStore;

    public JwtAuthFilter(JwtService jwtService, TokenStore tokenStore) {
        this.jwtService = jwtService;
        this.tokenStore = tokenStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            AuthUser user = authenticate(header.substring(7));
            if (user != null) {
                var authorities = user.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
                var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }

    /** Returns null for invalid tokens; the security chain then answers 401 for protected routes. */
    public AuthUser authenticate(String token) {
        try {
            Claims claims = jwtService.parse(token, JwtService.TokenType.ACCESS);
            if (tokenStore.isBlacklisted(claims.getId())) {
                return null;
            }
            @SuppressWarnings("unchecked")
            List<String> roles = claims.get("roles", List.class);
            return new AuthUser(Long.parseLong(claims.getSubject()), roles == null ? List.of() : roles,
                    claims.getId(), claims.getExpiration().toInstant());
        } catch (ApiException e) {
            return null;
        }
    }
}
