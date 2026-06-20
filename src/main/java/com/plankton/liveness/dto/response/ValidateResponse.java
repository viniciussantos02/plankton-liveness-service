package com.plankton.liveness.dto.response;

import com.plankton.liveness.enums.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resposta da validação biométrica.
 *
 * <p>{@code verificationToken} é um placeholder até a História 6 implementar a emissão de JWT real.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidateResponse {

    /** Status resultante da validação — sempre {@link SessionStatus#VALIDATED} em caso de sucesso. */
    private SessionStatus status;

    /**
     * Token de verificação biométrica.
     * Valor fixo {@code "PENDING_JWT"} até a História 6 implementar a emissão de JWT real.
     */
    private String verificationToken;
}
