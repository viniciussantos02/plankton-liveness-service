package com.plankton.liveness.service;

import com.plankton.liveness.adapter.AwsRekognitionAdapter;
import com.plankton.liveness.adapter.S3StorageAdapter;
import com.plankton.liveness.dto.response.ValidateResponse;
import com.plankton.liveness.enums.SessionStatus;
import com.plankton.liveness.exception.SessionNotFoundException;
import com.plankton.liveness.model.LivenessSession;
import com.plankton.liveness.util.CryptoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Orquestração da validação biométrica de liveness e face match.
 *
 * <p>Fluxo:
 * <ul>
 *   <li><b>Cenário A</b> (primeira biometria): detectFaces → upload S3 como referência</li>
 *   <li><b>Cenário B</b> (biometria recorrente): detectFaces → compareFaces → sobrescreve referência no S3</li>
 * </ul>
 *
 * <p>O CPF nunca é persistido em texto puro — apenas seu HMAC-SHA256 compõe a chave S3.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LivenessValidationService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final AwsRekognitionAdapter rekognitionAdapter;
    private final S3StorageAdapter s3StorageAdapter;
    private final CryptoUtils cryptoUtils;

    /**
     * Executa a validação biométrica completa para a sessão informada.
     *
     * @param sessionId  identificador UUID da sessão criada na História 2
     * @param imageBytes bytes da selfie enviada pelo cliente via multipart/form-data
     * @return {@link ValidateResponse} com status VALIDATED e token placeholder até História 6
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
        log.info("Validating biometrics. sessionId={}, deviceId={}", sessionId, deviceId);

        // 3. Montar chave S3 ofuscada — o CPF nunca aparece na chave
        String obfuscatedKey = "profiles/" + cryptoUtils.hash(cpf) + "/reference.png";

        // 4. [PONTO DE EXTENSÃO — História 5: Device Trust]
        // Aqui será verificado se o deviceId está na lista de dispositivos confiáveis.
        // Exemplo futuro: deviceTrustService.check(deviceId, cpf)

        // 5. Verificar se já existe imagem de referência no S3
        boolean referenceExists = s3StorageAdapter.exists(obfuscatedKey);

        if (!referenceExists) {
            // CENÁRIO A: primeira biometria — sem comparação de rostos
            log.info("Reference image not found. Running Scenario A (first biometric). sessionId={}", sessionId);

            // a. Validar liveness
            rekognitionAdapter.validateLiveness(imageBytes);

            // b. Persistir como imagem de referência
            s3StorageAdapter.upload(obfuscatedKey, imageBytes);

            // c. [PONTO DE EXTENSÃO — História 5: Device Trust]
            // Registrar deviceId como confiável após primeira biometria aprovada.
            // Exemplo futuro: deviceTrustService.register(deviceId, cpf)

            log.info("Scenario A completed. Reference image stored. sessionId={}", sessionId);

        } else {
            // CENÁRIO B: biometria recorrente — liveness + face match
            log.info("Reference image found. Running Scenario B (recurring biometric). sessionId={}", sessionId);

            // a. Validar liveness
            rekognitionAdapter.validateLiveness(imageBytes);

            // b. Comparar com imagem de referência via S3Object pointer (sem download)
            rekognitionAdapter.compareFaces(obfuscatedKey, imageBytes);

            // c. Sobrescrever referência com a selfie mais recente
            s3StorageAdapter.upload(obfuscatedKey, imageBytes);

            // d. [PONTO DE EXTENSÃO — História 5: Device Trust]
            // Atualizar registro de confiança do dispositivo após biometria recorrente aprovada.
            // Exemplo futuro: deviceTrustService.refresh(deviceId, cpf)

            log.info("Scenario B completed. Reference image updated. sessionId={}", sessionId);
        }

        // Retornar resposta com token placeholder — JWT real será emitido na História 6
        return ValidateResponse.builder()
                .status(SessionStatus.VALIDATED)
                .verificationToken("PENDING_JWT")
                .build();
    }
}
