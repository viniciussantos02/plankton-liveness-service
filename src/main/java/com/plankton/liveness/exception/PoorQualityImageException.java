package com.plankton.liveness.exception;

/**
 * Exceção lançada quando a qualidade da imagem (brightness ou sharpness) está
 * abaixo do mínimo aceitável, impedindo análise biométrica confiável.
 */
public class PoorQualityImageException extends RuntimeException {

    public PoorQualityImageException(String detail) {
        super("Image quality insufficient: " + detail);
    }
}
