# História 4 — Integração AWS Rekognition e Orquestração de Validação

## Objetivo
Implementar o endpoint de validação biométrica com toda a orquestração de decisão: verificação de sessão, chamadas ao Rekognition e escrita no S3.

## Dependências
- História 2 concluída (sessão Redis funcionando)
- História 3 concluída (S3StorageAdapter e CryptoUtils disponíveis)

## Arquivos a Criar/Modificar

| Arquivo | Pacote | Ação |
|---------|--------|------|
| `AwsConfig.java` | `config` | Adicionar bean `RekognitionClient` |
| `AwsRekognitionAdapter.java` | `adapter` | Novo: integração com Rekognition |
| `LivenessValidationService.java` | `service` | Novo: orquestração do fluxo completo |
| `LivenessSessionController.java` | `controller` | Adicionar endpoint `/validate` |
| `SessionNotFoundException.java` | `exception` | Novo |
| `SpoofingDetectedException.java` | `exception` | Novo |
| `FaceMismatchException.java` | `exception` | Novo |
| `PoorQualityImageException.java` | `exception` | Novo |
| `ValidateResponse.java` | `dto/response` | Novo |
| `LivenessValidationExceptionHandler.java` | `handler` | Novo: `@ControllerAdvice` para erros de validação |

## Especificação Técnica

### `AwsConfig.java` — adição do `RekognitionClient`
```java
@Bean
public RekognitionClient rekognitionClient() {
    return RekognitionClient.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
}
```

### `AwsRekognitionAdapter.java`

#### Método `validateLiveness(byte[] imageBytes)`
- Chama `rekognitionClient.detectFaces()`
- Passa imagem via bytes em memória: `Image.builder().bytes(SdkBytes.fromByteArray(imageBytes))`
- Verifica `FaceDetail.confidence()` ≥ threshold configurado em `rekognition.liveness.confidence-threshold`
- Se nenhum rosto detectado ou confiança abaixo do threshold → lança `SpoofingDetectedException`
- Se qualidade da imagem insuficiente (brightness/sharpness baixos) → lança `PoorQualityImageException`
- Retorna `true` se aprovado

#### Método `compareFaces(String sourceReferenceKey, byte[] targetCurrentBytes)`
- Chama `rekognitionClient.compareFaces()`
- **Imagem de origem (referência S3):** via pointer `Image.builder().s3Object(S3Object.builder().bucket(bucket).name(sourceReferenceKey).build())`
- **Imagem de destino (selfie atual):** via bytes `Image.builder().bytes(SdkBytes.fromByteArray(targetCurrentBytes))`
- Verifica `FaceMatch.similarity()` ≥ threshold configurado em `rekognition.face-match.similarity-threshold`
- Se similaridade abaixo do threshold → lança `FaceMismatchException`
- Retorna `true` se aprovado

**CRÍTICO:** nunca usar `GetObject` do S3 para buscar a referência. Sempre usar S3Object pointer no Rekognition.

### `LivenessValidationService.java` — Orquestração

```
validateBiometrics(sessionId, imageBytes):

1. Buscar sessão no Redis por sessionId
   → Não encontrada: lança SessionNotFoundException

2. Extrair cpf, deviceId da sessão

3. Montar obfuscatedKey = "profiles/" + cryptoUtils.hash(cpf) + "/reference.png"

4. (Device Trust será adicionado na História 5 — deixar ponto de extensão)

5. Verificar s3StorageAdapter.exists(obfuscatedKey)

   [CENÁRIO A — não existe]:
   a. rekognitionAdapter.validateLiveness(imageBytes)
   b. s3StorageAdapter.upload(obfuscatedKey, imageBytes)
   c. (registrar device trust — História 5)
   d. Retornar ValidateResponse(VALIDATED, jwt com ACTIVE_VERIFIED) — JWT História 6

   [CENÁRIO B — existe]:
   a. rekognitionAdapter.validateLiveness(imageBytes)
   b. rekognitionAdapter.compareFaces(obfuscatedKey, imageBytes)
   c. s3StorageAdapter.upload(obfuscatedKey, imageBytes)  // sobrescreve
   d. (registrar device trust — História 5)
   e. Retornar ValidateResponse(VALIDATED, jwt com ACTIVE_VERIFIED) — JWT História 6
```

Para Histórias 5 e 6 ainda não implementadas, retornar um token placeholder para não bloquear a orquestração.

### `LivenessSessionController.java` — adição do endpoint de validação

```
POST /api/v1/liveness/sessions/{sessionId}/validate
Content-Type: multipart/form-data
Authorization: Bearer <JWT>

@RequestParam("file") MultipartFile file
@PathVariable String sessionId
```

Extrair `byte[]` do `MultipartFile` antes de passar ao service.

### Exceções Customizadas

| Classe | Quando lançar |
|--------|---------------|
| `SessionNotFoundException` | sessionId não encontrado no Redis |
| `SpoofingDetectedException` | detectFaces retorna confiança abaixo do threshold ou nenhum rosto |
| `FaceMismatchException` | compareFaces retorna similaridade abaixo do threshold |
| `PoorQualityImageException` | imagem com qualidade insuficiente para análise |

### `LivenessValidationExceptionHandler.java`

| Exceção | HTTP Status | reason |
|---------|-------------|--------|
| `SessionNotFoundException` | 422 | `SESSION_NOT_FOUND` |
| `SpoofingDetectedException` | 422 | `SPOOFING_DETECTED` |
| `FaceMismatchException` | 422 | `FACE_MISMATCH` |
| `PoorQualityImageException` | 422 | `POOR_QUALITY` |

Todos retornam `ErrorResponse` com `status: REJECTED`.

### `ValidateResponse.java`
```java
public class ValidateResponse {
    private SessionStatus status;       // VALIDATED
    private String verificationToken;   // JWT (placeholder até História 6)
}
```

## Contrato de API (extraído do openapi.yaml)

**Request:**
```http
POST /api/v1/liveness/sessions/{sessionId}/validate
Authorization: Bearer <token>
Content-Type: multipart/form-data

file: <binary>
```

**Response 200:**
```json
{
  "status": "VALIDATED",
  "verificationToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**Response 422 (exemplos):**
```json
{ "status": "REJECTED", "reason": "SESSION_NOT_FOUND", "message": "A sessão de liveness informada expirou ou não existe." }
{ "status": "REJECTED", "reason": "SPOOFING_DETECTED", "message": "A análise de biometria facial indicou uma possível tentativa de fraude." }
{ "status": "REJECTED", "reason": "FACE_MISMATCH", "message": "O rosto enviado não corresponde ao cadastro existente." }
{ "status": "REJECTED", "reason": "POOR_QUALITY", "message": "A análise de biometria facial indicou que a qualidade da imagem é insuficiente para validação, tente novamente." }
```

## Acceptance Criteria

- [ ] `POST /validate` aceita `multipart/form-data` com campo `file`
- [ ] Sessão inexistente ou expirada retorna `422` com `SESSION_NOT_FOUND`
- [ ] detectFaces usa bytes em memória (nunca ponteiro S3)
- [ ] compareFaces usa S3Object pointer para imagem de referência (nunca download)
- [ ] Cenário A executa: detectFaces → upload S3 (sem compareFaces)
- [ ] Cenário B executa: detectFaces → compareFaces → upload S3 (sobrescrita)
- [ ] Thresholds lidos de `application.yml` (nunca hardcoded)
- [ ] `SpoofingDetectedException` → HTTP 422 + `SPOOFING_DETECTED`
- [ ] `FaceMismatchException` → HTTP 422 + `FACE_MISMATCH`
- [ ] `PoorQualityImageException` → HTTP 422 + `POOR_QUALITY`
- [ ] Nenhuma resposta de erro expõe stack trace

## O que NÃO deve ser feito
- Não fazer download da imagem de referência do S3 no microsserviço
- Não hardcodar thresholds de confiança
- Não misturar lógica de Device Trust nesta história (será na História 5)
- Não emitir JWT real nesta história (placeholder aceitável; JWT real na História 6)
