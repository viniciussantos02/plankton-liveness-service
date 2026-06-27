package com.plankton.liveness.adapter;

import com.plankton.liveness.exception.FaceMismatchException;
import com.plankton.liveness.exception.PoorQualityImageException;
import com.plankton.liveness.exception.SpoofingDetectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.CompareFacesMatch;
import software.amazon.awssdk.services.rekognition.model.CompareFacesRequest;
import software.amazon.awssdk.services.rekognition.model.CompareFacesResponse;
import software.amazon.awssdk.services.rekognition.model.ComparedFace;
import software.amazon.awssdk.services.rekognition.model.DetectFacesRequest;
import software.amazon.awssdk.services.rekognition.model.DetectFacesResponse;
import software.amazon.awssdk.services.rekognition.model.FaceDetail;
import software.amazon.awssdk.services.rekognition.model.ImageQuality;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AwsRekognitionAdapterTest {

    private static final String BUCKET = "plankton-liveness-biometrics";
    private static final String REFERENCE_KEY = "profiles/deadbeefhash/reference.png";
    private static final byte[] IMAGE_BYTES = new byte[]{1, 2, 3, 4};
    private static final float LIVENESS_THRESHOLD = 90.0f;
    private static final float SIMILARITY_THRESHOLD = 90.0f;
    private static final float MIN_BRIGHTNESS = 30.0f;
    private static final float MIN_SHARPNESS = 30.0f;

    @Mock
    private RekognitionClient rekognitionClient;

    private AwsRekognitionAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AwsRekognitionAdapter(rekognitionClient);
        ReflectionTestUtils.setField(adapter, "bucket", BUCKET);
        ReflectionTestUtils.setField(adapter, "livenessConfidenceThreshold", LIVENESS_THRESHOLD);
        ReflectionTestUtils.setField(adapter, "faceMatchSimilarityThreshold", SIMILARITY_THRESHOLD);
        ReflectionTestUtils.setField(adapter, "minBrightness", MIN_BRIGHTNESS);
        ReflectionTestUtils.setField(adapter, "minSharpness", MIN_SHARPNESS);
    }

    private FaceDetail faceDetail(float confidence, Float brightness, Float sharpness) {
        FaceDetail.Builder builder = FaceDetail.builder().confidence(confidence);
        if (brightness != null && sharpness != null) {
            builder.quality(ImageQuality.builder()
                    .brightness(brightness)
                    .sharpness(sharpness)
                    .build());
        }
        return builder.build();
    }

    // ---------- validateLiveness ----------

    @Test
    void validateLiveness_quandoFaceValidaEQualidadeBoa_naoLancaExcecao() {
        DetectFacesResponse response = DetectFacesResponse.builder()
                .faceDetails(faceDetail(99.5f, 80.0f, 80.0f))
                .build();
        when(rekognitionClient.detectFaces(any(DetectFacesRequest.class))).thenReturn(response);

        assertThatCode(() -> adapter.validateLiveness(IMAGE_BYTES)).doesNotThrowAnyException();

        ArgumentCaptor<DetectFacesRequest> captor = ArgumentCaptor.forClass(DetectFacesRequest.class);
        verify(rekognitionClient).detectFaces(captor.capture());
        assertThatCode(() -> captor.getValue().image().bytes()).doesNotThrowAnyException();
    }

    @Test
    void validateLiveness_quandoQualityNula_naoLancaExcecao() {
        DetectFacesResponse response = DetectFacesResponse.builder()
                .faceDetails(faceDetail(99.5f, null, null))
                .build();
        when(rekognitionClient.detectFaces(any(DetectFacesRequest.class))).thenReturn(response);

        assertThatCode(() -> adapter.validateLiveness(IMAGE_BYTES)).doesNotThrowAnyException();
    }

    @Test
    void validateLiveness_quandoNenhumRostoDetectado_lancaSpoofing() {
        DetectFacesResponse response = DetectFacesResponse.builder()
                .faceDetails(List.of())
                .build();
        when(rekognitionClient.detectFaces(any(DetectFacesRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> adapter.validateLiveness(IMAGE_BYTES))
                .isInstanceOf(SpoofingDetectedException.class);
    }

    @Test
    void validateLiveness_quandoConfiancaAbaixoDoThreshold_lancaSpoofing() {
        DetectFacesResponse response = DetectFacesResponse.builder()
                .faceDetails(faceDetail(50.0f, 80.0f, 80.0f))
                .build();
        when(rekognitionClient.detectFaces(any(DetectFacesRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> adapter.validateLiveness(IMAGE_BYTES))
                .isInstanceOf(SpoofingDetectedException.class);
    }

    @Test
    void validateLiveness_quandoBrilhoAbaixoDoMinimo_lancaPoorQuality() {
        DetectFacesResponse response = DetectFacesResponse.builder()
                .faceDetails(faceDetail(99.5f, 10.0f, 80.0f))
                .build();
        when(rekognitionClient.detectFaces(any(DetectFacesRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> adapter.validateLiveness(IMAGE_BYTES))
                .isInstanceOf(PoorQualityImageException.class);
    }

    @Test
    void validateLiveness_quandoNitidezAbaixoDoMinimo_lancaPoorQuality() {
        DetectFacesResponse response = DetectFacesResponse.builder()
                .faceDetails(faceDetail(99.5f, 80.0f, 10.0f))
                .build();
        when(rekognitionClient.detectFaces(any(DetectFacesRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> adapter.validateLiveness(IMAGE_BYTES))
                .isInstanceOf(PoorQualityImageException.class);
    }

    // ---------- compareFaces ----------

    @Test
    void compareFaces_quandoSimilaridadeAcimaDoThreshold_naoLancaExcecao() {
        CompareFacesResponse response = CompareFacesResponse.builder()
                .faceMatches(CompareFacesMatch.builder()
                        .similarity(98.0f)
                        .face(ComparedFace.builder().build())
                        .build())
                .build();
        when(rekognitionClient.compareFaces(any(CompareFacesRequest.class))).thenReturn(response);

        assertThatCode(() -> adapter.compareFaces(REFERENCE_KEY, IMAGE_BYTES)).doesNotThrowAnyException();

        ArgumentCaptor<CompareFacesRequest> captor = ArgumentCaptor.forClass(CompareFacesRequest.class);
        verify(rekognitionClient).compareFaces(captor.capture());
        CompareFacesRequest sent = captor.getValue();
        // Referência via S3 pointer (nunca download), selfie via bytes
        assertThat(sent.sourceImage().s3Object().bucket()).isEqualTo(BUCKET);
        assertThat(sent.sourceImage().s3Object().name()).isEqualTo(REFERENCE_KEY);
        assertThat(sent.targetImage().bytes()).isNotNull();
    }

    @Test
    void compareFaces_quandoNenhumMatch_lancaFaceMismatch() {
        CompareFacesResponse response = CompareFacesResponse.builder()
                .faceMatches(List.of())
                .build();
        when(rekognitionClient.compareFaces(any(CompareFacesRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> adapter.compareFaces(REFERENCE_KEY, IMAGE_BYTES))
                .isInstanceOf(FaceMismatchException.class);
    }

    @Test
    void compareFaces_quandoSimilaridadeAbaixoDoThreshold_lancaFaceMismatch() {
        CompareFacesResponse response = CompareFacesResponse.builder()
                .faceMatches(CompareFacesMatch.builder()
                        .similarity(50.0f)
                        .face(ComparedFace.builder().build())
                        .build())
                .build();
        when(rekognitionClient.compareFaces(any(CompareFacesRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> adapter.compareFaces(REFERENCE_KEY, IMAGE_BYTES))
                .isInstanceOf(FaceMismatchException.class);
    }
}
