package com.plankton.liveness.enums;

/**
 * Estado da sessão de liveness.
 * Valores conforme {@code openapi.yaml}.
 */
public enum SessionStatus {
    INITIALIZED,
    VALIDATED,
    REJECTED
}
