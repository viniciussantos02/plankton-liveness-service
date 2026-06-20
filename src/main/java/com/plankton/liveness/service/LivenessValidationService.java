package com.plankton.liveness.service;

import com.plankton.liveness.adapter.AwsRekognitionAdapter;
import com.plankton.liveness.adapter.S3StorageAdapter;
import com.plankton.liveness.dto.response.ValidateResponse;
import com.plankton.liveness.enums.SessionStatus;
import com.plankton.liveness.enums.TokenState;
import com.plankton.liveness.exception.SessionNotFoundException;
import com.plankton.liveness.model.LivenessSession;
import com.plankton.liveness.security.JwtTokenProvider;
import com.plankton.liveness.util.CryptoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Orquestração da validação biométrica de liveness e face match.
 *
 * <p>Fluxo:
 * <ol>
 *   <li>Buscar sessão no Redis</li>
 *   <li>Extrair cpf, deviceId, origin da sessão</li>
 *   <li>Verificar Device Trust (Fast Track)</li>
 *   <li>Verificar existência de referência no S3 → Cenário A ou B</li>
 *   <li>Rekognition + upload S3</li>
 *   <li>Registrar Device Trust com TTL 30 dias</li>
 *   <li>Retornar ValidateResponse com JWT</li>
 * </ol>
 *
 * <p>O CPF nunca é persistido em texto puro — apenas seu HMAC-SHA256 compõe a chave S3.
 * A chave de Device Trust usa o CPF em plain text conforme especificação (História 5).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LivenessValidationService {

    private static final Duration DEVICE_TRUST_TTL = Duration.ofDays(30);

    private final RedisTemplate<String, Object> redisTemplate;
    private final AwsRekognitionAdapter rekognitionAdapter;
    private final S3StorageAdapter s3StorageAdapter;
    private final CryptoUtils cryptoUtils;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Executa a validação biométrica completa para a sessão informada.
     *
     * @param sessionId  identificador UUID da sessão criada na História 2
     * @param imageBytes bytes da selfie enviada pelo cliente via multipart/form-data
     * @return {@link ValidateResponse} com status VALIDATED e JWT assinado
     * @throws SessionNotFoundException   se a sessão não existir ou tiver expirado no Redis
     * @throws com.plankton.liveness.exception.SpoofingDetectedException  se liveness reprovado
     * @throws com.plankton.liveness.exception.FaceMismatchException      se face match reprovado (Cenário B)
     * @throws com.plankton.liveness.exception.PoorQualityImageException  se qualidade insuficiente
     */
    public ValidateResponse validateBiometrics(String sessionId, byte[] imageBytes) {

        // 1. Buscar sessão no Redis
        LivenessSession session = (LivenessSession) redisTemplate.opsForValue().get(sessionId);
        if (session == null) {
            log.warn("Session not found or expired. sessionId={}", sessionId);
            throw new SessionNotFoundException(sessionId);
        }

        // 2. Extrair dados da sessão
        String cpf      = session.getCpf();
        String deviceId = session.getDeviceId();
        String origin   = session.getOrigin();
        log.info("Validating biometrics. sessionId={}, deviceId={}", sessionId, deviceId);

        // 3. Verificar Device Trust — Fast Track
        // A chave usa CPF em plain text (nunca hasheado), conforme especificação H5
        String trustKey = "device:trust:" + deviceId + ":" + cpf;
        boolean isTrusted = redisTemplate.hasKey(trustKey);

        if (isTrusted) {
            log.info("Device trust hit — Fast Track activated. sessionId={}, deviceId={}", sessionId, deviceId);
            redisTemplate.delete(sessionId);
            return new ValidateResponse(SessionStatus.VALIDATED,
                    jwtTokenProvider.generate(cpf, deviceId, origin, TokenState.ACTIVE_BY_CACHE));
        }

        // 4. Montar chave S3 ofuscada — o CPF nunca aparece na chave S3
        String obfuscatedKey = "profiles/" + cryptoUtils.hash(cpf) + "/reference.png";

        // 5. Verificar se já existe imagem de referência no S3
        boolean referenceExists = s3StorageAdapter.exists(obfuscatedKey);

        if (!referenceExists) {
            // CENÁRIO A: primeira biometria — sem comparação de rostos
            log.info("Reference image not found. Running Scenario A (first biometric). sessionId={}", sessionId);

            // a. Validar liveness
            rekognitionAdapter.validateLiveness(imageBytes);

            // b. Persistir como imagem de referência
            s3StorageAdapter.upload(obfuscatedKey, imageBytes);

            // 6. Registrar Device Trust com TTL 30 dias (Cenário A)
            redisTemplate.opsForValue().set(trustKey, "trusted", DEVICE_TRUST_TTL);
            redisTemplate.delete(sessionId);
            log.info("Scenario A completed. Reference image stored and device trust registered. sessionId={}", sessionId);

        } else {
            // CENÁRIO B: biometria recorrente — liveness + face match
            log.info("Reference image found. Running Scenario B (recurring biometric). sessionId={}", sessionId);

            // a. Validar liveness
            rekognitionAdapter.validateLiveness(imageBytes);

            // b. Comparar com imagem de referência via S3Object pointer (sem download)
            rekognitionAdapter.compareFaces(obfuscatedKey, imageBytes);

            // c. Sobrescrever referência com a selfie mais recente
            s3StorageAdapter.upload(obfuscatedKey, imageBytes);

            // 6. Registrar/renovar Device Trust com TTL 30 dias (Cenário B)
            redisTemplate.opsForValue().set(trustKey, "trusted", DEVICE_TRUST_TTL);
            redisTemplate.delete(sessionId);
            log.info("Scenario B completed. Reference image updated and device trust refreshed. sessionId={}", sessionId);
        }

        // 7. Retornar resposta com JWT assinado — aprovação via IA
        return new ValidateResponse(SessionStatus.VALIDATED,
                jwtTokenProvider.generate(cpf, deviceId, origin, TokenState.ACTIVE_VERIFIED));
    }
}
