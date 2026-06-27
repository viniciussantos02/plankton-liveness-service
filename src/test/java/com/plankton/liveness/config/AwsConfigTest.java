package com.plankton.liveness.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

class AwsConfigTest {

    private AwsConfig config;

    @BeforeEach
    void setUp() {
        config = new AwsConfig();
        ReflectionTestUtils.setField(config, "region", "us-east-1");
    }

    @Test
    void s3Client_criaBeanNaoNulo() {
        try (S3Client client = config.s3Client()) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void rekognitionClient_criaBeanNaoNulo() {
        try (RekognitionClient client = config.rekognitionClient()) {
            assertThat(client).isNotNull();
        }
    }
}
