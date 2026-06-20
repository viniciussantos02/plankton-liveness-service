package com.plankton.liveness.service;

import com.plankton.liveness.dto.request.CreateSessionRequest;
import com.plankton.liveness.dto.response.CreateSessionResponse;
import com.plankton.liveness.enums.SessionStatus;
import com.plankton.liveness.model.LivenessSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Criação e persistência temporária de sessões de liveness no Redis.
 * Aplica TTL estrito de 5 minutos (300 segundos).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LivenessSessionService {

    /** TTL estrito da sessão: 5 minutos. */
    public static final int SESSION_TTL_SECONDS = 300;

    private final RedisTemplate<String, Object> redisTemplate;

    public CreateSessionResponse createSession(CreateSessionRequest request, String deviceId) {
        String sessionId = UUID.randomUUID().toString();

        LivenessSession session = LivenessSession.builder()
                .id(sessionId)
                .cpf(request.getCpf())
                .deviceId(deviceId)
                .origin(request.getOrigin())
                .status(SessionStatus.INITIALIZED)
                .build();

        redisTemplate.opsForValue().set(sessionId, session, Duration.ofSeconds(SESSION_TTL_SECONDS));

        log.info("Liveness session created. sessionId={}, ttlSeconds={}", sessionId, SESSION_TTL_SECONDS);

        return CreateSessionResponse.builder()
                .sessionId(sessionId)
                .status(SessionStatus.INITIALIZED)
                .ttlInSeconds(SESSION_TTL_SECONDS)
                .build();
    }
}
