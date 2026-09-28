package com.faceswap.auth;

import com.faceswap.auth.AuthDtos.LoginRequest;
import com.faceswap.auth.AuthDtos.RegisterRequest;
import com.faceswap.auth.AuthDtos.TokenResponse;
import com.faceswap.auth.AuthDtos.UserDto;
import com.faceswap.common.ApiException;
import com.faceswap.user.Role;
import com.faceswap.user.RoleRepository;
import com.faceswap.user.User;
import com.faceswap.user.UserRepository;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenStore tokenStore;
    // Compared against when the email is unknown so response time doesn't reveal which emails exist.
    private final String dummyHash;

    public AuthService(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
                       JwtService jwtService, TokenStore tokenStore) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenStore = tokenStore;
        this.dummyHash = passwordEncoder.encode("timing-equalizer");
    }

    @Transactional
    public TokenResponse register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "Bu e-posta ile kayıtlı bir hesap var");
        }
        User user = new User(email, passwordEncoder.encode(req.password()), req.displayName());
        user.getRoles().add(roles.findByName(Role.USER).orElseThrow());
        users.save(user);
        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest req) {
        User user = users.findByEmailIgnoreCase(req.email().trim()).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean matches = passwordEncoder.matches(req.password(), hash);
        if (user == null || !matches || !user.isEnabled()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "E-posta veya şifre hatalı");
        }
        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse refresh(String refreshToken) {
        Claims claims = jwtService.parse(refreshToken, JwtService.TokenType.REFRESH);
        if (!tokenStore.consumeRefresh(claims.getId())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Oturum sona erdi, tekrar giriş yapın");
        }
        User user = users.findById(Long.parseLong(claims.getSubject()))
                .filter(User::isEnabled)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Kullanıcı bulunamadı"));
        return issueTokens(user);
    }

    public void logout(AuthUser user, String refreshToken) {
        tokenStore.blacklistAccess(user.jti(), user.tokenExpiresAt());
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                tokenStore.consumeRefresh(jwtService.parse(refreshToken, JwtService.TokenType.REFRESH).getId());
            } catch (ApiException ignored) {
                // An already invalid refresh token needs no revocation.
            }
        }
    }

    @Transactional(readOnly = true)
    public UserDto me(long userId) {
        return users.findById(userId).map(AuthService::toDto).orElseThrow(() -> ApiException.notFound("Kullanıcı"));
    }

    private TokenResponse issueTokens(User user) {
        var access = jwtService.accessToken(user.getId(), user.roleNames());
        var refresh = jwtService.refreshToken(user.getId());
        tokenStore.saveRefresh(refresh.jti(), user.getId(), refresh.expiresAt());
        return new TokenResponse(access.token(), access.expiresAt(), refresh.token(), refresh.expiresAt(), toDto(user));
    }

    private static UserDto toDto(User user) {
        return new UserDto(user.getId(), user.getEmail(), user.getDisplayName(), user.roleNames());
    }
}
