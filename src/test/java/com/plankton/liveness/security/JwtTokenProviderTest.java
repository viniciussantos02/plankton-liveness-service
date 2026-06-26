package com.plankton.liveness.security;

import com.plankton.liveness.enums.TokenState;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String SECRET = "test-jwt-secret-key-with-at-least-32-bytes-1234567890";
    private static final String CPF = "12345678901";
    private static final String DEVICE_ID = "bbb111_origem";
    private static final String ORIGIN = "bank123";

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "secret", SECRET);
        ReflectionTestUtils.setField(jwtTokenProvider, "ttlSeconds", 300L);
    }

    private Claims parse(String token) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @Test
    void generate_containsCorrectClaims() {
        String token = jwtTokenProvider.generate(CPF, DEVICE_ID, ORIGIN, TokenState.ACTIVE_VERIFIED);

        Claims claims = parse(token);

        assertThat(claims.getIssuer()).isEqualTo("plankton-liveness");
        assertThat(claims.getSubject()).isEqualTo(CPF);
        assertThat(claims.get("deviceId", String.class)).isEqualTo(DEVICE_ID);
        assertThat(claims.get("origin", String.class)).isEqualTo(ORIGIN);
        assertThat(claims.get("state", String.class)).isEqualTo("ACTIVE_VERIFIED");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    void generate_activeVerifiedState() {
        String token = jwtTokenProvider.generate(CPF, DEVICE_ID, ORIGIN, TokenState.ACTIVE_VERIFIED);

        assertThat(parse(token).get("state", String.class)).isEqualTo("ACTIVE_VERIFIED");
    }

    @Test
    void generate_activeByCacheState() {
        String token = jwtTokenProvider.generate(CPF, DEVICE_ID, ORIGIN, TokenState.ACTIVE_BY_CACHE);

        assertThat(parse(token).get("state", String.class)).isEqualTo("ACTIVE_BY_CACHE");
    }

    @Test
    void generate_issuerIsPlanktonLiveness() {
        String token = jwtTokenProvider.generate(CPF, DEVICE_ID, ORIGIN, TokenState.ACTIVE_VERIFIED);

        assertThat(parse(token).getIssuer()).isEqualTo("plankton-liveness");
    }

    @Test
    void generate_subjectIsCpf() {
        String token = jwtTokenProvider.generate(CPF, DEVICE_ID, ORIGIN, TokenState.ACTIVE_VERIFIED);

        assertThat(parse(token).getSubject()).isEqualTo(CPF);
    }

    @Test
    void generate_tokenExpires_isRejectedOnParse() {
        // TTL negativo força um token já expirado no momento da geração, garantindo
        // o teste de expiração de forma determinística (sem depender de Thread.sleep).
        ReflectionTestUtils.setField(jwtTokenProvider, "ttlSeconds", -10L);

        String expiredToken = jwtTokenProvider.generate(CPF, DEVICE_ID, ORIGIN, TokenState.ACTIVE_VERIFIED);

        assertThatThrownBy(() -> parse(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void generate_signedWithConfiguredSecret_invalidSecretRejected() {
        String token = jwtTokenProvider.generate(CPF, DEVICE_ID, ORIGIN, TokenState.ACTIVE_VERIFIED);

        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "a-totally-different-secret-key-with-at-least-32-bytes-xyz".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> Jwts.parser()
                .verifyWith(wrongKey)
                .build()
                .parseSignedClaims(token))
                .isInstanceOf(io.jsonwebtoken.security.SignatureException.class);
    }
}
