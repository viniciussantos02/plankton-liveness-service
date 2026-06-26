package com.plankton.liveness.adapter;

import com.plankton.liveness.exception.FaceMismatchException;
import com.plankton.liveness.exception.PoorQualityImageException;
import com.plankton.liveness.exception.SpoofingDetectedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.Attribute;
import software.amazon.awssdk.services.rekognition.model.CompareFacesRequest;
import software.amazon.awssdk.services.rekognition.model.CompareFacesResponse;
import software.amazon.awssdk.services.rekognition.model.DetectFacesRequest;
import software.amazon.awssdk.services.rekognition.model.DetectFacesResponse;
import software.amazon.awssdk.services.rekognition.model.FaceDetail;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.S3Object;

/**
 * Adapter de comunicação com o Amazon Rekognition.
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>Detecção de liveness via {@code detectFaces} (sempre usando bytes em memória)</li>
 *   <li>Comparação de rostos via {@code compareFaces} (referência via S3 pointer, selfie via bytes)</li>
 * </ul>
 *
 * <p>CRÍTICO: nunca realiza download (GetObject) da imagem de referência do S3.
 * O Rekognition acessa a referência diretamente via S3Object pointer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwsRekognitionAdapter {

    private final RekognitionClient rekognitionClient;

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Value("${rekognition.liveness.confidence-threshold}")
    private float livenessConfidenceThreshold;

    @Value("${rekognition.face-match.similarity-threshold}")
    private float faceMatchSimilarityThreshold;

    @Value("${rekognition.image-quality.min-brightness}")
    private float minBrightness;

    @Value("${rekognition.image-quality.min-sharpness}")
    private float minSharpness;

    public void validateLiveness(byte[] imageBytes) {
        Image image = Image.builder()
                .bytes(SdkBytes.fromByteArray(imageBytes))
                .build();

        DetectFacesRequest request = DetectFacesRequest.builder()
                .image(image)
                .attributes(Attribute.ALL)
                .build();

        DetectFacesResponse response = rekognitionClient.detectFaces(request);

        if (response.faceDetails().isEmpty()) {
            log.warn("Rekognition detectFaces: no face detected in image");
            throw new SpoofingDetectedException("No face detected in image");
        }

        // Avalia apenas o rosto com maior confiança (primeiro da lista — Rekognition ordena por confiança)
        FaceDetail faceDetail = response.faceDetails().getFirst();

        float confidence = faceDetail.confidence();
        log.debug("Rekognition detectFaces: confidence={}", confidence);

        if (confidence < livenessConfidenceThreshold) {
            log.warn("Rekognition detectFaces: confidence={} below threshold={}", confidence, livenessConfidenceThreshold);
            throw new SpoofingDetectedException(
                    String.format("Face confidence %.2f%% is below liveness threshold %.2f%%", confidence, livenessConfidenceThreshold));
        }

        // Verificação de qualidade de imagem
        if (faceDetail.quality() != null) {
            float brightness = faceDetail.quality().brightness();
            float sharpness  = faceDetail.quality().sharpness();
            log.debug("Rekognition detectFaces: brightness={}, sharpness={}", brightness, sharpness);

            if (brightness < minBrightness || sharpness < minSharpness) {
                throw new PoorQualityImageException(
                        String.format("brightness=%.2f, sharpness=%.2f", brightness, sharpness));
            }
        }

        log.info("Rekognition liveness validated. confidence={}", confidence);
    }

    /**
     * Compara o rosto atual com a imagem de referência armazenada no S3.
     *
     * <p>CRÍTICO: a imagem de referência é acessada via S3Object pointer — o Rekognition
     * lê diretamente do S3 sem que este serviço faça GetObject. Nunca baixamos a referência.
     *
     * @param sourceReferenceKey chave S3 da imagem de referência (ex: "profiles/&lt;hash&gt;/reference.png")
     * @param targetCurrentBytes bytes da selfie atual enviada pelo cliente
     * @throws FaceMismatchException se a similaridade estiver abaixo do threshold configurado
     */
    public void compareFaces(String sourceReferenceKey, byte[] targetCurrentBytes) {
        Image sourceImage = Image.builder()
                .s3Object(S3Object.builder()
                        .bucket(bucket)
                        .name(sourceReferenceKey)
                        .build())
                .build();

        Image targetImage = Image.builder()
                .bytes(SdkBytes.fromByteArray(targetCurrentBytes))
                .build();

        CompareFacesRequest request = CompareFacesRequest.builder()
                .sourceImage(sourceImage)
                .targetImage(targetImage)
                .similarityThreshold(faceMatchSimilarityThreshold)
                .build();

        CompareFacesResponse response = rekognitionClient.compareFaces(request);

        if (response.faceMatches().isEmpty()) {
            log.warn("Rekognition compareFaces: no matching face found. referenceKey={}", sourceReferenceKey);
            throw new FaceMismatchException(0.0f, faceMatchSimilarityThreshold);
        }

        float similarity = response.faceMatches().getFirst().similarity();
        log.debug("Rekognition compareFaces: similarity={}", similarity);

        if (similarity < faceMatchSimilarityThreshold) {
            log.warn("Rekognition compareFaces: similarity={} below threshold={}", similarity, faceMatchSimilarityThreshold);
            throw new FaceMismatchException(similarity, faceMatchSimilarityThreshold);
        }

        log.info("Rekognition face match validated. similarity={}", similarity);
    }
}
