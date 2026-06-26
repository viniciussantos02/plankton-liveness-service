package com.plankton.liveness.handler;

import com.plankton.liveness.dto.response.ErrorResponse;
import com.plankton.liveness.enums.RejectionReason;
import com.plankton.liveness.enums.SessionStatus;
import com.plankton.liveness.exception.SessionInvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class LivenessSessionExceptionHandlerTest {

    private LivenessSessionExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LivenessSessionExceptionHandler();
    }

    @Test
    void handleSessionInvalidRequest_retornaBadRequestComInvalidRequest() {
        SessionInvalidRequestException ex =
                new SessionInvalidRequestException("Dados inválidos");

        ResponseEntity<ErrorResponse> response = handler.handleSessionInvalidRequest(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(SessionStatus.REJECTED.name());
        assertThat(response.getBody().getReason()).isEqualTo(RejectionReason.INVALID_REQUEST);
    }
}
