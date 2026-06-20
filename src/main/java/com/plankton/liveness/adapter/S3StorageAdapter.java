package com.plankton.liveness.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Adapter de comunicação com o Amazon S3.
 * Responsabilidade única: envio e verificação de existência de objetos.
 * Não implementa download/getObject — o modelo de privacidade proíbe leitura direta.
 * Versionamento de objetos é gerenciado pelo bucket via Terraform — não controlado aqui.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3StorageAdapter {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket}")
    private String bucket;

    /**
     * Verifica se um objeto existe no bucket sem realizar download dos dados.
     * Operação leve baseada em HeadObject (apenas metadados).
     *
     * @param obfuscatedKey chave S3 já ofuscada (hash do CPF) — nunca o CPF original
     * @return {@code true} se o objeto existir; {@code false} se não encontrado
     * @throws software.amazon.awssdk.awscore.exception.AwsServiceException em erros AWS que não sejam NoSuchKeyException
     */
    public boolean exists(String obfuscatedKey) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(obfuscatedKey)
                    .build());
            log.debug("S3 object exists. bucket={}, key={}", bucket, obfuscatedKey);
            return true;
        } catch (NoSuchKeyException e) {
            log.debug("S3 object not found. bucket={}, key={}", bucket, obfuscatedKey);
            return false;
        }
    }

    /**
     * Faz upload de um objeto para o bucket S3 com content-type image/png.
     * Os bytes são enviados inteiramente em memória — sem escrita em disco.
     *
     * @param obfuscatedKey chave S3 já ofuscada (hash do CPF) — nunca o CPF original
     * @param content       conteúdo binário da imagem PNG
     * @throws software.amazon.awssdk.awscore.exception.AwsServiceException em erros de comunicação com o S3
     */
    public void upload(String obfuscatedKey, byte[] content) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(obfuscatedKey)
                .contentType("image/png")
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(content));
        log.info("S3 object uploaded. bucket={}, key={}, sizeBytes={}", bucket, obfuscatedKey, content.length);
    }
}
