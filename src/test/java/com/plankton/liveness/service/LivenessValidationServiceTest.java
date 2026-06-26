package com.plankton.liveness.service;

import com.plankton.liveness.adapter.AwsRekognitionAdapter;
import com.plankton.liveness.adapter.S3StorageAdapter;
import com.plankton.liveness.dto.response.ValidateResponse;
import com.plankton.liveness.enums.SessionStatus;
import com.plankton.liveness.enums.TokenState;
import com.plankton.liveness.exception.FaceMismatchException;
import com.plankton.liveness.exception.PoorQualityImageException;
import com.plankton.liveness.exception.SessionNotFoundException;
import com.plankton.liveness.exception.SpoofingDetectedException;
import com.plankton.liveness.model.LivenessSession;
import com.plankton.liveness.security.JwtTokenProvider;
import com.plankton.liveness.util.CryptoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LivenessValidationServiceTest {

    private static final String SESSION_ID = "6a7b8c9d-e1f2-3a4b-5c6d-7e8f9a0b1c2d";
    private static final String CPF = "12345678901";
    private static final String DEVICE_ID = "bbb111_origem";
    private static final String ORIGIN = "bank123";
    private static final String CPF_HASH = "deadbeefhash";
    private static final String EXPECTED_KEY = "profiles/" + CPF_HASH + "/reference.png";
    private static final String TRUST_KEY = "device:trust:" + DEVICE_ID + ":" + CPF;
    private static final byte[] IMAGE_BYTES = new byte[]{1, 2, 3, 4};

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOperations;
    @Mock
    private AwsRekognitionAdapter rekognitionAdapter;
    @Mock
    private S3StorageAdapter s3StorageAdapter;
    @Mock
    private CryptoUtils cryptoUtils;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private LivenessValidationService service;

    private LivenessSession session;

    @BeforeEach
    void setUp() {
        session = LivenessSession.builder()
                .id(SESSION_ID)
                .cpf(CPF)
                .deviceId(DEVICE_ID)
                .origin(ORIGIN)
                .status(SessionStatus.INITIALIZED)
                .build();
    }

    private void stubSessionFound() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(SESSION_ID)).thenReturn(session);
    }

    @Test
    void validate_sessionNotFound_throws422() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(SESSION_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.validateBiometrics(SESSION_ID, IMAGE_BYTES))
                .isInstanceOf(SessionNotFoundException.class);

        verify(s3StorageAdapter, never()).exists(any());
        verify(rekognitionAdapter, never()).validateLiveness(any());
        verify(jwtTokenProvider, never()).generate(any(), any(), any(), any());
    }

    @Test
    void validate_deviceTrusted_fastTrack() {
        stubSessionFound();
        when(redisTemplate.hasKey(TRUST_KEY)).thenReturn(true);
        when(jwtTokenProvider.generate(CPF, DEVICE_ID, ORIGIN, TokenState.ACTIVE_BY_CACHE))
                .thenReturn("jwt-by-cache");

        ValidateResponse response = service.validateBiometrics(SESSION_ID, IMAGE_BYTES);

        assertThat(response.getStatus()).isEqualTo(SessionStatus.VALIDATED);
        assertThat(response.getVerificationToken()).isEqualTo("jwt-by-cache");
        verify(jwtTokenProvider).generate(CPF, DEVICE_ID, ORIGIN, TokenState.ACTIVE_BY_CACHE);
    }

    @Test
    void validate_deviceTrustNotCalled_onFastTrack() {
        stubSessionFound();
        when(redisTemplate.hasKey(TRUST_KEY)).thenReturn(true);
        when(jwtTokenProvider.generate(any(), any(), any(), any())).thenReturn("jwt-by-cache");

        service.validateBiometrics(SESSION_ID, IMAGE_BYTES);

        verify(s3StorageAdapter, never()).exists(any());
        verify(s3StorageAdapter, never()).upload(any(), any());
        verify(rekognitionAdapter, never()).validateLiveness(any());
        verify(rekognitionAdapter, never()).compareFaces(any(), any());
        verify(redisTemplate).delete(SESSION_ID);
    }

    @Test
    void validate_scenarioA_newUser_success() {
        stubSessionFound();
        when(redisTemplate.hasKey(TRUST_KEY)).thenReturn(false);
        when(cryptoUtils.hash(CPF)).thenReturn(CPF_HASH);
        when(s3StorageAdapter.exists(EXPECTED_KEY)).thenReturn(false);
        when(jwtTokenProvider.generate(CPF, DEVICE_ID, ORIGIN, TokenState.ACTIVE_VERIFIED))
                .thenReturn("jwt-verified");

        ValidateResponse response = service.validateBiometrics(SESSION_ID, IMAGE_BYTES);

        assertThat(response.getStatus()).isEqualTo(SessionStatus.VALIDATED);
        assertThat(response.getVerificationToken()).isEqualTo("jwt-verified");

        verify(rekognitionAdapter).validateLiveness(IMAGE_BYTES);
        verify(rekognitionAdapter, never()).compareFaces(any(), any());
        verify(s3StorageAdapter).upload(eq(EXPECTED_KEY), eq(IMAGE_BYTES));
        verify(jwtTokenProvider).generate(CPF, DEVICE_ID, ORIGIN, TokenState.ACTIVE_VERIFIED);
    }

    @Test
    void validate_scenarioB_existingUser_success() {
        stubSessionFound();
        when(redisTemplate.hasKey(TRUST_KEY)).thenReturn(false);
        when(cryptoUtils.hash(CPF)).thenReturn(CPF_HASH);
        when(s3StorageAdapter.exists(EXPECTED_KEY)).thenReturn(true);
        when(jwtTokenProvider.generate(CPF, DEVICE_ID, ORIGIN, TokenState.ACTIVE_VERIFIED))
                .thenReturn("jwt-verified");

        ValidateResponse response = service.validateBiometrics(SESSION_ID, IMAGE_BYTES);

        assertThat(response.getStatus()).isEqualTo(SessionStatus.VALIDATED);
        assertThat(response.getVerificationToken()).isEqualTo("jwt-verified");

        verify(rekognitionAdapter).validateLiveness(IMAGE_BYTES);
        verify(rekognitionAdapter).compareFaces(eq(EXPECTED_KEY), eq(IMAGE_BYTES));
        verify(s3StorageAdapter).upload(eq(EXPECTED_KEY), eq(IMAGE_BYTES));
        verify(jwtTokenProvider).generate(CPF, DEVICE_ID, ORIGIN, TokenState.ACTIVE_VERIFIED);
    }

    @Test
    void validate_deviceTrustRegistered_afterAiApproval() {
        stubSessionFound();
        when(redisTemplate.hasKey(TRUST_KEY)).thenReturn(false);
        when(cryptoUtils.hash(CPF)).thenReturn(CPF_HASH);
        when(s3StorageAdapter.exists(EXPECTED_KEY)).thenReturn(false);
        when(jwtTokenProvider.generate(any(), any(), any(), any())).thenReturn("jwt-verified");

        service.validateBiometrics(SESSION_ID, IMAGE_BYTES);

        verify(valueOperations).set(eq(TRUST_KEY), eq("trusted"), eq(Duration.ofDays(30)));
        verify(redisTemplate).delete(SESSION_ID);
    }

    @Test
    void validate_scenarioA_spoofingDetected() {
        stubSessionFound();
        when(redisTemplate.hasKey(TRUST_KEY)).thenReturn(false);
        when(cryptoUtils.hash(CPF)).thenReturn(CPF_HASH);
        when(s3StorageAdapter.exists(EXPECTED_KEY)).thenReturn(false);
        doThrow(new SpoofingDetectedException("No face detected in image"))
                .when(rekognitionAdapter).validateLiveness(IMAGE_BYTES);

        assertThatThrownBy(() -> service.validateBiometrics(SESSION_ID, IMAGE_BYTES))
                .isInstanceOf(SpoofingDetectedException.class);

        verify(s3StorageAdapter, never()).upload(any(), any());
        verify(rekognitionAdapter, never()).compareFaces(any(), any());
        verify(jwtTokenProvider, never()).generate(any(), any(), any(), any());
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void validate_scenarioB_faceMismatch() {
        stubSessionFound();
        when(redisTemplate.hasKey(TRUST_KEY)).thenReturn(false);
        when(cryptoUtils.hash(CPF)).thenReturn(CPF_HASH);
        when(s3StorageAdapter.exists(EXPECTED_KEY)).thenReturn(true);
        doThrow(new FaceMismatchException(40.0f, 90.0f))
                .when(rekognitionAdapter).compareFaces(EXPECTED_KEY, IMAGE_BYTES);

        assertThatThrownBy(() -> service.validateBiometrics(SESSION_ID, IMAGE_BYTES))
                .isInstanceOf(FaceMismatchException.class);

        verify(rekognitionAdapter).validateLiveness(IMAGE_BYTES);
        verify(s3StorageAdapter, never()).upload(any(), any());
        verify(jwtTokenProvider, never()).generate(any(), any(), any(), any());
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void validate_poorQualityImage() {
        stubSessionFound();
        when(redisTemplate.hasKey(TRUST_KEY)).thenReturn(false);
        when(cryptoUtils.hash(CPF)).thenReturn(CPF_HASH);
        when(s3StorageAdapter.exists(EXPECTED_KEY)).thenReturn(false);
        doThrow(new PoorQualityImageException("brightness=10.00, sharpness=5.00"))
                .when(rekognitionAdapter).validateLiveness(IMAGE_BYTES);

        assertThatThrownBy(() -> service.validateBiometrics(SESSION_ID, IMAGE_BYTES))
                .isInstanceOf(PoorQualityImageException.class);

        verify(s3StorageAdapter, never()).upload(any(), any());
        verify(jwtTokenProvider, never()).generate(any(), any(), any(), any());
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    }
}
