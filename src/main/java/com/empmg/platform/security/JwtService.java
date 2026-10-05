package com.empmg.platform.security;

import com.empmg.platform.common.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** JWT access tokens only. Refresh tokens are opaque random strings stored hashed in the DB. */
@Service
@RequiredArgsConstructor
public class JwtService {

    private final AppProperties props;

    private SecretKey accessKey;

    @PostConstruct
    void init() {
        String access = props.getJwt().getAccessSecret();
        if (access == null || access.length() < 32) {
            throw new IllegalStateException(
                    "JWT_ACCESS_SECRET is not set or too short (use at least 32 chars). Copy .env.example to .env.");
        }
        this.accessKey = Keys.hmacShaKeyFor(access.getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccess(AuthPrincipal p) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(p.userId()))
                .claim("cid", p.companyId())
                .claim("role", p.role().name())
                .claim("email", p.email())
                .claim("emp", p.employeeId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(props.getJwt().getAccessTtlMinutes()))))
                .signWith(accessKey)
                .compact();
    }

    /** Parses and validates an access token; returns the principal or null if invalid/expired. */
    public AuthPrincipal parseAccess(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(accessKey).build()
                    .parseSignedClaims(token).getPayload();
            return new AuthPrincipal(
                    Long.valueOf(c.getSubject()),
                    c.get("cid", Long.class),
                    c.get("email", String.class),
                    AccessRole.valueOf(c.get("role", String.class)),
                    c.get("emp", Long.class));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
