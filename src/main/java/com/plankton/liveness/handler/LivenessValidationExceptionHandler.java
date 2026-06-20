package com.plankton.liveness.handler;

import com.plankton.liveness.dto.response.ErrorResponse;
import com.plankton.liveness.enums.RejectionReason;
import com.plankton.liveness.enums.SessionStatus;
import com.plankton.liveness.exception.FaceMismatchException;
import com.plankton.liveness.exception.PoorQualityImageException;
import com.plankton.liveness.exception.SessionNotFoundException;
import com.plankton.liveness.exception.SpoofingDetectedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Tratamento centralizado de erros da validação biométrica.
 *
 * <p>Todas as exceções retornam HTTP 422 com {@link ErrorResponse} opaco.
 * Nenhuma resposta expõe stack trace, mensagens internas ou dados sensíveis.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = com.plankton.liveness.controller.LivenessSessionController.class)
public class LivenessValidationExceptionHandler {

    private static final String REJECTED = SessionStatus.REJECTED.name();

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSessionNotFound(SessionNotFoundException ex) {
        log.warn("Session not found during validation: {}", ex.getMessage());
        return buildResponse(RejectionReason.SESSION_NOT_FOUND,
                "Sessão não encontrada ou expirada. Inicie uma nova sessão e tente novamente");
    }

    @ExceptionHandler(SpoofingDetectedException.class)
    public ResponseEntity<ErrorResponse> handleSpoofingDetected(SpoofingDetectedException ex) {
        log.warn("Spoofing detected during liveness validation: {}", ex.getMessage());
        return buildResponse(RejectionReason.SPOOFING_DETECTED,
                "A análise de biometria facial indicou uma possível tentativa de fraude.");
    }

    @ExceptionHandler(FaceMismatchException.class)
    public ResponseEntity<ErrorResponse> handleFaceMismatch(FaceMismatchException ex) {
        log.warn("Face mismatch detected: {}", ex.getMessage());
        return buildResponse(RejectionReason.FACE_MISMATCH,
                "O rosto enviado não corresponde ao cadastro existente.");
    }

    @ExceptionHandler(PoorQualityImageException.class)
    public ResponseEntity<ErrorResponse> handlePoorQuality(PoorQualityImageException ex) {
        log.warn("Poor quality image rejected: {}", ex.getMessage());
        return buildResponse(RejectionReason.POOR_QUALITY,
                "A análise de biometria facial indicou que a qualidade da imagem é insuficiente para validação, tente novamente.");
    }

    private ResponseEntity<ErrorResponse> buildResponse(RejectionReason reason, String message) {
        ErrorResponse body = ErrorResponse.builder()
                .status(REJECTED)
                .reason(reason)
                .message(message)
                .build();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }
}
