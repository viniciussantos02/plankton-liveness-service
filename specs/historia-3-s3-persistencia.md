# História 3 — Camada de Abstração de Persistência Otimizada no S3

## Objetivo
Criar a camada de integração com o S3 real, aplicando hash determinístico no CPF e expondo somente as operações de verificação de existência e upload.

## Dependências
- História 1 concluída (projeto configurado, bucket S3 provisionado via Terraform)

## Arquivos a Criar

| Arquivo | Pacote | Descrição |
|---------|--------|-----------|
| `AwsConfig.java` | `config` | Bean de `S3Client` (RekognitionClient virá na História 4) |
| `CryptoUtils.java` | `util` | Hash SHA-256 com salt configurável |
| `S3StorageAdapter.java` | `adapter` | Adapter de integração com S3 |

## Especificação Técnica

### `AwsConfig.java`
```java
@Configuration
public class AwsConfig {

    @Value("${aws.region}")
    private String region;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    // RekognitionClient será adicionado na História 4
}
```

Usar `DefaultCredentialsProvider` — nunca hardcodar Access Key / Secret Key.

### `CryptoUtils.java`
```java
@Component
public class CryptoUtils {

    @Value("${crypto.cpf-hash-secret}")
    private String secret;

    public String hash(String cpf) {
        // SHA-256 de (cpf + secret)
        // Retorna hex string lowercase
    }
}
```

**Propriedades do hash:**
- Determinístico: mesma entrada → mesmo resultado sempre
- Unidirecional: impossível recuperar CPF a partir do hash
- Salt via env var `${CPF_HASH_SECRET}` — nunca hardcoded

**Algoritmo:**
1. Concatenar `cpf + secret`
2. Aplicar `SHA-256`
3. Retornar representação hexadecimal lowercase

### `S3StorageAdapter.java`
```java
@Component
public class S3StorageAdapter {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket}")
    private String bucket;

    // Verificar se objeto existe no S3
    public boolean exists(String obfuscatedKey) {
        // s3Client.headObject(...)
        // Retorna true se existir, false se lançar NoSuchKeyException
    }

    // Fazer upload de bytes para o S3
    public void upload(String obfuscatedKey, byte[] content) {
        // s3Client.putObject(...)
        // Content-type: image/png
    }
}
```

**Contrato dos métodos:**

`exists(String obfuscatedKey)`:
- Parâmetro: chave já ofuscada, ex: `profiles/abc123.../reference.png`
- Usa `headObject` — operação leve, sem download de dados
- Retorna `true` se objeto existe
- Retorna `false` se `NoSuchKeyException` for lançada
- Propaga outras exceções de AWS

`upload(String obfuscatedKey, byte[] content)`:
- Parâmetro: chave já ofuscada + bytes da imagem
- Usa `putObject` com `RequestBody.fromBytes(content)`
- Não retorna valor
- O versionamento é gerenciado pelo bucket (configurado no Terraform); o adapter não precisa controlar isso

**Convenção de path no S3:**
```
profiles/{CryptoUtils.hash(cpf)}/reference.png
```

Esta convenção é montada pelo `LivenessValidationService` (História 4), não pelo adapter. O adapter recebe a chave já formatada.

## Acceptance Criteria

- [ ] `S3Client` bean configurado com `DefaultCredentialsProvider` e região via `application.yml`
- [ ] `CryptoUtils.hash(cpf)` produz resultado determinístico (mesmo input → mesmo output)
- [ ] `CryptoUtils.hash(cpf)` com secrets diferentes produz outputs diferentes
- [ ] `S3StorageAdapter.exists()` usa `headObject` (não `getObject`)
- [ ] `S3StorageAdapter.upload()` usa `putObject` com bytes em memória
- [ ] Nenhuma das classes faz download de objetos do S3
- [ ] O bucket é configurado via `${aws.s3.bucket}` — sem hardcode
- [ ] O secret do hash vem de `${crypto.cpf-hash-secret}` — sem hardcode

## O que NÃO deve ser feito
- Não criar método `download()` ou `getObject()` no adapter — o Rekognition lê do S3 diretamente via pointer
- Não salvar o CPF original como parte da chave S3
- Não instanciar `S3Client` fora do `@Bean` no `AwsConfig`
- Não implementar lógica de orquestração no adapter — apenas comunicação com S3
