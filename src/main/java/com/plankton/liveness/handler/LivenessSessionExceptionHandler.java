package com.plankton.liveness.handler;

import com.plankton.liveness.dto.response.ErrorResponse;
import com.plankton.liveness.enums.RejectionReason;
import com.plankton.liveness.enums.SessionStatus;
import com.plankton.liveness.exception.SessionInvalidRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Tratamento centralizado de erros de criação de sessão.
 * Sempre retorna {@link ErrorResponse} opaco, sem stack trace ou dados internos.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = com.plankton.liveness.controller.LivenessSessionController.class)
public class LivenessSessionExceptionHandler {

    private static final String INVALID_REQUEST_MESSAGE =
            "Problemas ao iniciar a sessão de liveness. Verifique os dados enviados e tente novamente.";

    @ExceptionHandler(SessionInvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleSessionInvalidRequest(SessionInvalidRequestException ex) {
        log.warn("Invalid session request: {}", ex.getMessage());
        return buildResponse(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        log.warn("Session request validation failed: {}", ex.getMessage());
        return buildResponse(INVALID_REQUEST_MESSAGE);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        log.warn("Missing required header: {}", ex.getHeaderName());
        return buildResponse(INVALID_REQUEST_MESSAGE);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Unreadable session request body");
        return buildResponse(INVALID_REQUEST_MESSAGE);
    }

    private ResponseEntity<ErrorResponse> buildResponse(String message) {
        ErrorResponse body = ErrorResponse.builder()
                .status(SessionStatus.REJECTED.name())
                .reason(RejectionReason.INVALID_REQUEST)
                .message(message)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
