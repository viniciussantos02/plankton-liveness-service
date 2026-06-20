package com.plankton.liveness.exception;

/**
 * Exceção lançada quando o Rekognition não detecta um rosto real na imagem
 * ou quando a confiança do detectFaces está abaixo do threshold configurado.
 * Indica possível tentativa de fraude (spoofing).
 */
public class SpoofingDetectedException extends RuntimeException {

    public SpoofingDetectedException(String reason) {
        super(reason);
    }
}
