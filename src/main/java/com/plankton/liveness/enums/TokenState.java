package com.plankton.liveness.enums;

/**
 * Estado do token JWT emitido ao término da validação biométrica.
 *
 * <ul>
 *   <li>{@link #ACTIVE_VERIFIED} — aprovado via Rekognition (liveness + face match via IA)</li>
 *   <li>{@link #ACTIVE_BY_CACHE} — aprovado via Device Trust (Fast Track, sem acionar IA)</li>
 * </ul>
 */
public enum TokenState {
    ACTIVE_VERIFIED,
    ACTIVE_BY_CACHE
}
