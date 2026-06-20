package com.plankton.liveness.exception;

/**
 * Exceção lançada quando os dados de criação de sessão são inválidos.
 * Carrega a mensagem opaca a ser exibida ao cliente.
 */
public class SessionInvalidRequestException extends RuntimeException {

    public SessionInvalidRequestException(String message) {
        super(message);
    }
}
