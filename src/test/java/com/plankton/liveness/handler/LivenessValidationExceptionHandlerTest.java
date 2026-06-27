package com.plankton.liveness.handler;

import com.plankton.liveness.dto.response.ErrorResponse;
import com.plankton.liveness.enums.RejectionReason;
import com.plankton.liveness.enums.SessionStatus;
import com.plankton.liveness.exception.FaceMismatchException;
import com.plankton.liveness.exception.PoorQualityImageException;
import com.plankton.liveness.exception.SessionNotFoundException;
import com.plankton.liveness.exception.SpoofingDetectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class LivenessValidationExceptionHandlerTest {

    private LivenessValidationExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LivenessValidationExceptionHandler();
    }

    @Test
    void handleSessionNotFound_retornaUnprocessableEntityComSessionNotFound() {
        SessionNotFoundException ex = new SessionNotFoundException("sessão expirada");

        ResponseEntity<ErrorResponse> response = handler.handleSessionNotFound(ex);

        assertResponse(response, RejectionReason.SESSION_NOT_FOUND);
    }

    @Test
    void handleSpoofingDetected_retornaUnprocessableEntityComSpoofingDetected() {
        SpoofingDetectedException ex = new SpoofingDetectedException("possível fraude");

        ResponseEntity<ErrorResponse> response = handler.handleSpoofingDetected(ex);

        assertResponse(response, RejectionReason.SPOOFING_DETECTED);
    }

    @Test
    void handleFaceMismatch_retornaUnprocessableEntityComFaceMismatch() {
        FaceMismatchException ex = new FaceMismatchException(50.0f, 90.0f);

        ResponseEntity<ErrorResponse> response = handler.handleFaceMismatch(ex);

        assertResponse(response, RejectionReason.FACE_MISMATCH);
    }

    @Test
    void handlePoorQuality_retornaUnprocessableEntityComPoorQuality() {
        PoorQualityImageException ex = new PoorQualityImageException("brightness=10, sharpness=10");

        ResponseEntity<ErrorResponse> response = handler.handlePoorQuality(ex);

        assertResponse(response, RejectionReason.POOR_QUALITY);
    }

    private void assertResponse(ResponseEntity<ErrorResponse> response, RejectionReason expectedReason) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(SessionStatus.REJECTED.name());
        assertThat(response.getBody().getReason()).isEqualTo(expectedReason);
        assertThat(response.getBody().getMessage()).isNotBlank();
    }
}
