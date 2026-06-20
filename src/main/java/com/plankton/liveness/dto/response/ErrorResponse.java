package com.plankton.liveness.dto.response;

import com.plankton.liveness.enums.RejectionReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resposta padrão de erro exposta ao cliente.
 * Nunca expõe stack trace ou dados internos.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    private String status;
    private RejectionReason reason;
    private String message;
}
