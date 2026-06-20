package com.plankton.liveness.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Corpo da requisição de criação de sessão de liveness.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateSessionRequest {

    @NotBlank
    @Pattern(regexp = "^\\d{11}$")
    private String cpf;

    @NotBlank
    private String origin;
}
