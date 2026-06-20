package com.plankton.liveness.model;

import com.plankton.liveness.enums.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Modelo da sessão de liveness persistido no Redis com TTL estrito de 5 minutos.
 * O CPF é guardado apenas internamente na sessão; a ofuscação para uso no S3
 * é responsabilidade da camada de persistência (História 3).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LivenessSession implements Serializable {

    private String id;
    private String cpf;
    private String deviceId;
    private String origin;
    private SessionStatus status;
}
