package com.plankton.liveness.dto.response;

import com.plankton.liveness.enums.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resposta de sucesso (HTTP 201) da criação de sessão de liveness.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSessionResponse {

    private String sessionId;
    private SessionStatus status;
    private int ttlInSeconds;
}
