package com.plankton.liveness.exception;

/**
 * Exceção lançada quando a sessão de liveness não é encontrada no Redis
 * (expirou, nunca existiu ou sessionId inválido).
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String sessionId) {
        super("Session not found: " + sessionId);
    }
}
