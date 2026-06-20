package com.plankton.liveness.enums;

/**
 * Motivo de rejeição da validação biométrica ou da criação de sessão.
 * Valores conforme {@code openapi.yaml}.
 */
public enum RejectionReason {
    SPOOFING_DETECTED,
    FACE_MISMATCH,
    POOR_QUALITY,
    SESSION_NOT_FOUND,
    INVALID_REQUEST
}
