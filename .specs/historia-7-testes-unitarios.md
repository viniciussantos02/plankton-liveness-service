# História 7 — Cobertura de Testes Unitários

## Objetivo
Criar testes unitários isolados com JUnit 5 e Mockito cobrindo todos os caminhos de negócio sem depender de serviços externos.

## Dependências
- Histórias 2 a 6 concluídas

## Regra Fundamental
- `S3StorageAdapter` e `AwsRekognitionAdapter` **sempre mockados** — nunca chamar AWS real
- Redis **mockado** via `@Mock RedisTemplate` — nunca usar Redis real nos testes unitários
- Todos os valores de `RejectionReason` devem ter ao menos um caso de teste

## Arquivos a Criar

```
src/test/java/com/plankton/liveness/
├── util/
│   └── CryptoUtilsTest.java
├── security/
│   └── JwtTokenProviderTest.java
└── service/
    └── LivenessValidationServiceTest.java
```

## Especificação dos Testes

### `CryptoUtilsTest.java`

| Teste | Descrição | Resultado Esperado |
|-------|-----------|-------------------|
| `hash_isDeterministic` | Chamar `hash(cpf)` duas vezes com mesmo input e mesmo secret | Outputs idênticos |
| `hash_differentSecrets_differentOutputs` | Mesmo CPF, secrets distintos | Outputs diferentes |
| `hash_differentCpfs_differentOutputs` | Mesmo secret, CPFs distintos | Outputs diferentes |
| `hash_notContainsCpf` | Verificar que o hash não contém o CPF | `assertFalse(hash.contains(cpf))` |
| `hash_returnsHexString` | Resultado deve ser string hexadecimal de 64 chars (SHA-256) | `hash.matches("[a-f0-9]{64}")` |

### `JwtTokenProviderTest.java`

| Teste | Descrição | Resultado Esperado |
|-------|-----------|-------------------|
| `generate_containsCorrectClaims` | Gerar token e parsear claims | `iss`, `sub`, `deviceId`, `origin`, `state` presentes |
| `generate_activeVerifiedState` | Gerar com `ACTIVE_VERIFIED` | Claim `state = "ACTIVE_VERIFIED"` |
| `generate_activeByCacheState` | Gerar com `ACTIVE_BY_CACHE` | Claim `state = "ACTIVE_BY_CACHE"` |
| `generate_issuerIsPlanktonLiveness` | Verificar issuer | `iss = "plankton-liveness"` |
| `generate_subjectIsCpf` | Verificar subject | `sub = cpf passado` |
| `generate_tokenExpires` | Gerar token com TTL 1s e aguardar expiração | Token inválido após expirar |

### `LivenessValidationServiceTest.java`

#### Setup
```java
@ExtendWith(MockitoExtension.class)
class LivenessValidationServiceTest {

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private S3StorageAdapter s3StorageAdapter;
    @Mock private AwsRekognitionAdapter rekognitionAdapter;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private CryptoUtils cryptoUtils;

    @InjectMocks private LivenessValidationService service;
}
```

#### Casos de Teste Obrigatórios

| Teste | Setup dos Mocks | Resultado Esperado |
|-------|-----------------|-------------------|
| `validate_sessionNotFound_throws422` | Redis retorna null para sessionId | `SessionNotFoundException` |
| `validate_deviceTrusted_fastTrack` | Redis retorna trust key existente | Retorna sem chamar S3 nem Rekognition; JWT com `ACTIVE_BY_CACHE` |
| `validate_scenarioA_newUser_success` | S3.exists = false; detectFaces = true | Chama upload S3; JWT com `ACTIVE_VERIFIED`; não chama compareFaces |
| `validate_scenarioB_existingUser_success` | S3.exists = true; detectFaces = true; compareFaces = true | Chama upload S3; JWT com `ACTIVE_VERIFIED`; chama compareFaces |
| `validate_scenarioA_spoofingDetected` | S3.exists = false; detectFaces lança `SpoofingDetectedException` | Propaga `SpoofingDetectedException`; não chama upload S3 |
| `validate_scenarioB_faceMismatch` | S3.exists = true; detectFaces = true; compareFaces lança `FaceMismatchException` | Propaga `FaceMismatchException`; não chama upload S3 |
| `validate_poorQualityImage` | detectFaces lança `PoorQualityImageException` | Propaga `PoorQualityImageException` |
| `validate_deviceTrustRegistered_afterAiApproval` | Cenário A ou B com sucesso | Verifica que `redisTemplate.opsForValue().set(trustKey, ...)` foi chamado com TTL 30 dias |
| `validate_deviceTrustNotCalled_onFastTrack` | Device trust ativo | Verifica que `s3StorageAdapter` e `rekognitionAdapter` nunca são invocados |

#### Verificações de Interação (Mockito.verify)
- Fast Track: `verify(s3StorageAdapter, never()).exists(any())`
- Fast Track: `verify(rekognitionAdapter, never()).validateLiveness(any())`
- Cenário A: `verify(rekognitionAdapter, never()).compareFaces(any(), any())`
- Cenário B: `verify(rekognitionAdapter).compareFaces(eq(expectedKey), any())`
- Sucesso IA: `verify(s3StorageAdapter).upload(eq(expectedKey), any())`

## Acceptance Criteria

- [ ] Todos os testes passam sem conectar a AWS ou Redis real
- [ ] `CryptoUtils` tem 5 cenários de teste cobrindo determinismo e segurança
- [ ] `JwtTokenProvider` tem 6 cenários cobrindo claims e expiração
- [ ] `LivenessValidationService` tem 9+ cenários cobrindo todos os fluxos e exceções
- [ ] Todos os valores de `RejectionReason` têm ao menos um teste correspondente:
  - `SESSION_NOT_FOUND` ✓
  - `SPOOFING_DETECTED` ✓
  - `FACE_MISMATCH` ✓
  - `POOR_QUALITY` ✓
  - `INVALID_REQUEST` ✓ (coberto nos testes do controller/handler)
- [ ] `verify(never())` confirma que Fast Track não chama AWS
- [ ] `verify(never()).compareFaces(...)` confirma que Cenário A não chama compareFaces

## O que NÃO deve ser feito
- Não usar `@SpringBootTest` nos testes unitários — usar `@ExtendWith(MockitoExtension.class)`
- Não instanciar `S3Client` ou `RekognitionClient` reais
- Não conectar a Redis real nos testes unitários
- Não criar testes de integração nesta história (essa é a História 8)
