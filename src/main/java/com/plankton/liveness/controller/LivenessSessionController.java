package com.plankton.liveness.controller;

import com.plankton.liveness.dto.request.CreateSessionRequest;
import com.plankton.liveness.dto.response.CreateSessionResponse;
import com.plankton.liveness.dto.response.ValidateResponse;
import com.plankton.liveness.service.LivenessSessionService;
import com.plankton.liveness.service.LivenessValidationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.plankton.liveness.exception.PoorQualityImageException;

/**
 * Endpoints de gerenciamento de sessões de liveness.
 */
@RestController
@RequestMapping("/api/v1/liveness/sessions")
@RequiredArgsConstructor
public class LivenessSessionController {

    private final LivenessSessionService livenessSessionService;
    private final LivenessValidationService livenessValidationService;

    @PostMapping
    public ResponseEntity<CreateSessionResponse> createSession(
            @RequestHeader("X-Device-ID") String deviceId,
            @Valid @RequestBody CreateSessionRequest request) {

        CreateSessionResponse response = livenessSessionService.createSession(request, deviceId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Valida a biometria facial para a sessão informada.
     *
     * <p>Recebe a selfie via {@code multipart/form-data} (campo {@code file}) e executa
     * liveness detection e, se houver referência anterior, face match.
     *
     * @param sessionId identificador UUID da sessão
     * @param file      selfie enviada pelo cliente
     * @return HTTP 200 com {@link ValidateResponse} em caso de sucesso
     */
    @PostMapping("/{sessionId}/validate")
    public ResponseEntity<ValidateResponse> validateSession(
            @PathVariable String sessionId,
            @RequestParam("file") MultipartFile file) {

        byte[] imageBytes;
        try {
            imageBytes = file.getBytes();
        } catch (Exception e) {
            throw new PoorQualityImageException("Failed to read uploaded file");
        }
        ValidateResponse response = livenessValidationService.validateBiometrics(sessionId, imageBytes);
        return ResponseEntity.ok(response);
    }
}
