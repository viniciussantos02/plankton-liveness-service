package com.plankton.liveness.security;

import com.plankton.liveness.enums.TokenState;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private static final int MIN_SECRET_BYTES = 32;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.ttl-seconds}")
    private long ttlSeconds;

    @PostConstruct
    void validate() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        Assert.isTrue(keyBytes.length >= MIN_SECRET_BYTES,
                "jwt.secret must be at least 32 bytes for HS256 — current length: " + keyBytes.length);
    }

    public String generate(String cpf, String deviceId, String origin, TokenState state) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        Instant now = Instant.now();
        Instant expiration = now.plus(Duration.ofSeconds(ttlSeconds));

        return Jwts.builder()
                .issuer("plankton-liveness")
                .subject(cpf)
                .claim("deviceId", deviceId)
                .claim("origin", origin)
                .claim("state", state.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(key)
                .compact();
    }
}
