package com.plankton.liveness.enums;

/**
 * Estado da sessão de liveness.
 * Valores conforme {@code plankton-liveness-service-api.yaml}.
 */
public enum SessionStatus {
    INITIALIZED,
    VALIDATED,
    REJECTED
}
