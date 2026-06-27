package com.plankton.liveness.adapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3StorageAdapterTest {

    private static final String BUCKET = "plankton-liveness-biometrics";
    private static final String KEY = "profiles/deadbeefhash/reference.png";

    @Mock
    private S3Client s3Client;

    private S3StorageAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new S3StorageAdapter(s3Client);
        ReflectionTestUtils.setField(adapter, "bucket", BUCKET);
    }

    @Test
    void exists_quandoObjetoEncontrado_retornaTrue() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

        boolean result = adapter.exists(KEY);

        assertThat(result).isTrue();

        ArgumentCaptor<HeadObjectRequest> captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3Client).headObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo(KEY);
    }

    @Test
    void exists_quandoNoSuchKey_retornaFalse() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        boolean result = adapter.exists(KEY);

        assertThat(result).isFalse();
    }

    @Test
    void exists_quandoErroAwsGenerico_propagaExcecao() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(AwsServiceException.builder().message("boom").build());

        assertThatThrownBy(() -> adapter.exists(KEY))
                .isInstanceOf(AwsServiceException.class);
    }

    @Test
    void upload_enviaObjetoComBucketKeyEContentType() {
        byte[] content = new byte[]{1, 2, 3, 4};

        adapter.upload(KEY, content);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo(KEY);
        assertThat(captor.getValue().contentType()).isEqualTo("image/png");
    }

    @Test
    void upload_quandoErroAws_propagaExcecao() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(AwsServiceException.builder().message("boom").build());

        assertThatThrownBy(() -> adapter.upload(KEY, new byte[]{1}))
                .isInstanceOf(AwsServiceException.class);
    }
}
