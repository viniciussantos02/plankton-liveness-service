# História 2 — Criação de Sessão e Persistência Temporária

## Objetivo
Expor o endpoint `POST /api/v1/liveness/sessions` para criar intenções de biometria e persistir a sessão no Redis com TTL de 5 minutos.

## Dependências
- História 1 concluída (projeto Spring Boot configurado com Redis disponível)

## Arquivos a Criar

| Arquivo | Pacote | Descrição |
|---------|--------|-----------|
| `RedisConfig.java` | `config` | Bean de `RedisTemplate` com serializadores |
| `LivenessSession.java` | `model` | Modelo da sessão |
| `SessionStatus.java` | `enums` | Enum de status |
| `RejectionReason.java` | `enums` | Enum de motivo de rejeição |
| `CreateSessionRequest.java` | `dto/request` | Request body do endpoint |
| `CreateSessionResponse.java` | `dto/response` | Response body do endpoint |
| `ErrorResponse.java` | `dto/response` | Response de erro (usado em toda a aplicação) |
| `LivenessSessionController.java` | `controller` | Controller REST |
| `LivenessSessionService.java` | `service` | Lógica de criação e persistência |
| `SessionInvalidRequestException.java` | `exception` | Exceção de request inválido |
| `LivenessSessionExceptionHandler.java` | `handler` | `@ControllerAdvice` para erros de sessão |

## Especificação Técnica

### `RedisConfig.java`
```java
@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        // StringRedisSerializer para key
        // GenericJackson2JsonRedisSerializer para value
    }
}
```

### `LivenessSession.java`
Campos obrigatórios: `id` (UUID), `cpf`, `deviceId`, `origin`, `status` (SessionStatus).

### `SessionStatus` (enum)
Valores exatos conforme `openapi.yaml`:
- `INITIALIZED`
- `VALIDATED`
- `REJECTED`

### `RejectionReason` (enum)
Valores exatos conforme `openapi.yaml`:
- `SPOOFING_DETECTED`
- `FACE_MISMATCH`
- `POOR_QUALITY`
- `SESSION_NOT_FOUND`
- `INVALID_REQUEST`

### `CreateSessionRequest.java`
```java
public class CreateSessionRequest {
    @NotBlank
    @Pattern(regexp = "^\\d{11}$")
    private String cpf;

    @NotBlank
    private String origin;
}
```

### `CreateSessionResponse.java`
```java
public class CreateSessionResponse {
    private String sessionId;
    private SessionStatus status;   // sempre INITIALIZED
    private int ttlInSeconds;       // sempre 300
}
```

### `ErrorResponse.java`
```java
public class ErrorResponse {
    private String status;          // sempre "REJECTED"
    private RejectionReason reason;
    private String message;
}
```

### `LivenessSessionController.java`
```
POST /api/v1/liveness/sessions
Header: X-Device-ID (obrigatório)
Body: CreateSessionRequest
Response 201: CreateSessionResponse
Response 400: ErrorResponse (INVALID_REQUEST)
```

### `LivenessSessionService.java`
- Gerar UUID para `sessionId`
- Montar objeto `LivenessSession` com status `INITIALIZED`
- Persistir no Redis com chave `sessionId` e TTL **300 segundos**
- Retornar `CreateSessionResponse`

### `SessionInvalidRequestException.java`
- Exceção customizada para erros de validação/criação de sessão
- Deve carregar a mensagem de erro a ser exibida ao cliente

### `LivenessSessionExceptionHandler.java`
- `@ControllerAdvice`
- Captura `SessionInvalidRequestException` → HTTP 400 + `ErrorResponse(REJECTED, INVALID_REQUEST, mensagem)`
- Captura `MethodArgumentNotValidException` → HTTP 400 + `ErrorResponse(REJECTED, INVALID_REQUEST, mensagem padrão)`

## Contrato de API (extraído do openapi.yaml)

**Request:**
```http
POST /api/v1/liveness/sessions
Content-Type: application/json
X-Device-ID: bbb111_origem

{ "cpf": "22222222222", "origin": "bank123" }
```

**Response 201:**
```json
{
  "sessionId": "6a7b8c9d-e1f2-3a4b-5c6d-7e8f9a0b1c2d",
  "status": "INITIALIZED",
  "ttlInSeconds": 300
}
```

**Response 400:**
```json
{
  "status": "REJECTED",
  "reason": "INVALID_REQUEST",
  "message": "Problemas ao iniciar a sessão de liveness. Verifique os dados enviados e tente novamente."
}
```

## Acceptance Criteria

- [ ] `POST /api/v1/liveness/sessions` responde `201` com `sessionId`, `status: INITIALIZED`, `ttlInSeconds: 300`
- [ ] Header `X-Device-ID` ausente ou CPF inválido retorna `400` com `reason: INVALID_REQUEST`
- [ ] Sessão salva no Redis com TTL de exatamente 300 segundos
- [ ] CPF com formato inválido (não 11 dígitos numéricos) é rejeitado na validação de entrada
- [ ] `ErrorResponse` nunca expõe stack trace ou dados internos
- [ ] Enum `SessionStatus` tem exatamente os valores: `INITIALIZED`, `VALIDATED`, `REJECTED`
- [ ] Enum `RejectionReason` tem exatamente os valores: `SPOOFING_DETECTED`, `FACE_MISMATCH`, `POOR_QUALITY`, `SESSION_NOT_FOUND`, `INVALID_REQUEST`

## O que NÃO deve ser feito
- Não implementar o endpoint de validação (`/validate`) nesta história
- Não salvar o CPF em plain text no Redis (sessão deve guardar o CPF apenas internamente na sessão — a ofuscação no S3 é feita na História 3)
- Não criar lógica de negócio além da criação/persistência da sessão
