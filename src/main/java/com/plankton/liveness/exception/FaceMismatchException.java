package com.plankton.liveness.exception;

/**
 * Exceção lançada quando a similaridade entre o rosto atual e a referência armazenada
 * está abaixo do threshold de face-match configurado em rekognition.face-match.similarity-threshold.
 */
public class FaceMismatchException extends RuntimeException {

    public FaceMismatchException(float similarity, float threshold) {
        super(String.format("Face similarity %.2f%% is below threshold %.2f%%", similarity, threshold));
    }
}
