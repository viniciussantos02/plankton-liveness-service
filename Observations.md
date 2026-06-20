# Revisão Final — plankton-liveness-service (Histórias 1–6)

> Data: 2026-06-20

---

## Sumário Executivo

**APROVADO COM RESSALVAS MENORES**

A implementação cobre com alta fidelidade todos os requisitos funcionais e não funcionais das Histórias 1 a 6. O fluxo de orquestração (Device Trust → S3 → Rekognition → JWT) está correto, sem vazamento de PII e sem download de objetos do S3. Os dois itens bloqueantes identificados na revisão inicial foram corrigidos: (1) sessão agora é deletada do Redis após cada uso nos três caminhos (Fast Track, Cenário A e Cenário B), garantindo uso único do `sessionId`; (2) mensagens de erro alinhadas com o contrato exato do `openapi.yaml` em todos os handlers. Permanecem dois débitos menores não bloqueantes: divergência de algoritmo de hash entre spec e código (melhoria intencional de segurança), e Javadoc desatualizado no `ValidateResponse.java`.

---

## Cobertura por História

### História 1 — Setup do Ecossistema e Infraestrutura Real AWS

**Veredito: APROVADO**

| Subtask | Status | Observação |
|---------|--------|------------|
| Spring Boot 21 LTS inicializado | APROVADO | `pom.xml` correto: `java.version=21`, `spring-boot-starter-parent 3.3.4` |
| Dependências obrigatórias no `pom.xml` | APROVADO | Spring Web, Data Redis, Validation, Lombok, AWS SDK v2 S3, Rekognition — todas presentes |
| JJWT adicionado (História 6) | APROVADO | `jjwt-api`, `jjwt-impl`, `jjwt-jackson` versão 0.12.6 presentes com escopos corretos |
| `docker-compose.yml` — apenas Redis e RedisInsight | APROVADO | `redis:7-alpine` na porta 6379 com healthcheck; `redisinsight:2.60` na porta 8001. Sem LocalStack |
| `main.tf` — provedor AWS `us-east-1`, sem hardcode de credenciais | APROVADO | `DefaultCredentialsProvider` via cadeia padrão da AWS |
| Bucket S3 com versionamento habilitado | APROVADO | `aws_s3_bucket_versioning status = "Enabled"` |
| Criptografia SSE-S3 | APROVADO | `sse_algorithm = "AES256"` com `bucket_key_enabled = true` |
| Bloqueio de acesso público | APROVADO | Todos os quatro flags de bloqueio ativados |
| Políticas IAM mínimas | APROVADO | `s3:PutObject`, `s3:GetObject`, `s3:HeadObject`, `rekognition:DetectFaces`, `rekognition:CompareFaces` |
| `application.yml` sem secrets em plain text | APROVADO | `${CPF_HASH_SECRET}` e `${JWT_SECRET}` via variáveis de ambiente |

---

### História 2 — Criação de Sessão e Persistência Temporária

**Veredito: APROVADO**

| Subtask | Status | Observação |
|---------|--------|------------|
| `RedisConfig.java` — `RedisTemplate` com serializadores | APROVADO | `StringRedisSerializer` para key, `GenericJackson2JsonRedisSerializer` para value; `afterPropertiesSet()` chamado corretamente |
| `LivenessSession.java` — campos obrigatórios | APROVADO | `id`, `cpf`, `deviceId`, `origin`, `status` presentes; `Serializable` implementado |
| `SessionStatus` — valores exatos | APROVADO | `INITIALIZED`, `VALIDATED`, `REJECTED` |
| `RejectionReason` — valores exatos | APROVADO | `SPOOFING_DETECTED`, `FACE_MISMATCH`, `POOR_QUALITY`, `SESSION_NOT_FOUND`, `INVALID_REQUEST` |
| `CreateSessionRequest.java` — `@NotBlank` + `@Pattern` no CPF | APROVADO | Regex `^\d{11}$` correta |
| `CreateSessionResponse.java` | APROVADO | `sessionId`, `status`, `ttlInSeconds` |
| `ErrorResponse.java` — sem stack trace | APROVADO | Apenas `status`, `reason`, `message` |
| `LivenessSessionController` — `POST /api/v1/liveness/sessions` | APROVADO | HTTP 201, header `X-Device-ID` obrigatório via `@RequestHeader` |
| `LivenessSessionService` — UUID + TTL 300s no Redis | APROVADO | `Duration.ofSeconds(300)` — valor hardcoded como constante `SESSION_TTL_SECONDS` |
| `SessionInvalidRequestException` | APROVADO | RuntimeException com mensagem opaca |
| `LivenessSessionExceptionHandler` — HTTP 400 para erros de sessão | APROVADO | Captura `SessionInvalidRequestException`, `MethodArgumentNotValidException`, `MissingRequestHeaderException`, `HttpMessageNotReadableException` |
| Handler escopado apenas ao controller de sessão | **ATENÇÃO MENOR** | `@RestControllerAdvice(assignableTypes = LivenessSessionController.class)` correto. O mesmo controller também recebe o endpoint `/validate`, então o `LivenessValidationExceptionHandler` igualmente escopa para o mesmo `assignableTypes`. Ambos os advisors escopo ao mesmo controller — funciona, mas pode gerar confusão de manutenção; avaliar separar controllers |
| Sessão removida do Redis após validação | **DÉBITO** | A sessão não é deletada do Redis após sucesso/falha no `/validate`. Fica ativa até expirar pelo TTL. Permite re-tentativas com o mesmo `sessionId` dentro de 5 minutos |

---

### História 3 — Camada de Abstração de Persistência Otimizada no S3

**Veredito: APROVADO COM RESSALVA**

| Subtask | Status | Observação |
|---------|--------|------------|
| `AwsConfig.java` — `S3Client` com `DefaultCredentialsProvider` e região via `@Value` | APROVADO | Implementação conforme spec |
| `CryptoUtils.java` — Hash determinístico com secret configurável | APROVADO FUNCIONAL / **DESVIO DE SPEC** | A spec (H3) define SHA-256 simples (`SHA-256 de (cpf + secret)`). A implementação usa **HMAC-SHA256** (`HmacSHA256` via `javax.crypto.Mac`). HMAC é criptograficamente **superior** ao SHA-256 simples com concatenação (resistente a ataques de extensão de comprimento). O teste de vetor em `CryptoUtilsTest` confirma o resultado via `openssl dgst -sha256 -hmac`, comprovando consistência. O desvio é uma melhoria de segurança, mas gera incompatibilidade com eventuais outros serviços que implementem SHA-256 simples seguindo o spec. Deve ser documentado como decisão técnica ou o spec deve ser atualizado |
| `CryptoUtils.hash()` — nunca hardcoda secret | APROVADO | Usa `${crypto.cpf-hash-secret}` via `@Value` |
| `S3StorageAdapter.exists()` — usa `headObject`, não `getObject` | APROVADO | Implementação correta; sem download de dados |
| `S3StorageAdapter.upload()` — usa `putObject` com `RequestBody.fromBytes()` | APROVADO | `contentType("image/png")` fixo — ver Observações de Qualidade |
| Nenhum método `download()` ou `getObject()` exposto | APROVADO | Contrato de privacidade respeitado |
| Bucket via `${aws.s3.bucket}` — sem hardcode | APROVADO | Usando `@Value` |
| Convenção de path montada no service, não no adapter | APROVADO | `"profiles/" + cryptoUtils.hash(cpf) + "/reference.png"` montado em `LivenessValidationService` |

---

### História 4 — Integração AWS Rekognition e Orquestração de Validação

**Veredito: APROVADO COM RESSALVAS**

| Subtask | Status | Observação |
|---------|--------|------------|
| `AwsConfig.java` — `RekognitionClient` adicionado | APROVADO | Mesma região e `DefaultCredentialsProvider` |
| `AwsRekognitionAdapter.validateLiveness()` — bytes em memória | APROVADO | `Image.builder().bytes(SdkBytes.fromByteArray(imageBytes))` |
| `AwsRekognitionAdapter.validateLiveness()` — threshold via `@Value` | APROVADO | `${rekognition.liveness.confidence-threshold}` |
| `AwsRekognitionAdapter.validateLiveness()` — avalia apenas 1º rosto | **ATENÇÃO MENOR** | Usa `response.faceDetails().getFirst()` — apenas o rosto de maior confiança. Se a imagem contiver múltiplos rostos, os demais são ignorados. Comportamento aceitável para selfie; pode gerar falso positivo se imagem contiver rosto ao fundo com maior confiança |
| `AwsRekognitionAdapter.compareFaces()` — S3Object pointer para referência | APROVADO | `Image.builder().s3Object(S3Object.builder().bucket(bucket).name(sourceReferenceKey))` — sem download |
| `AwsRekognitionAdapter.compareFaces()` — bytes em memória para selfie atual | APROVADO | Correto |
| Thresholds nunca hardcoded | APROVADO | Ambos lidos via `@Value` de `application.yml` |
| Qualidade de imagem (brightness/sharpness) via thresholds configuráveis | APROVADO PARCIAL | Thresholds `min-brightness` e `min-sharpness` em `application.yml` e `@Value` no adapter — não presentes na spec original da H4 (foi adicionado como melhoria). Positivo |
| `LivenessSessionController` — endpoint `/validate` com `MultipartFile` | APROVADO | `@RequestParam("file") MultipartFile file`, extrai `byte[]` antes de passar ao service |
| Exceção de leitura de arquivo mapeada para `PoorQualityImageException` | **ATENÇÃO** | `file.getBytes()` lança `IOException` (ex: arquivo corrompido/vazio) e o controller captura como `Exception` e relança como `PoorQualityImageException`. Semanticamente incorreto: erro de I/O na leitura do multipart não é "baixa qualidade". Deve ser uma exceção própria (ex: `InvalidImageException`) ou tratada como `INVALID_REQUEST` |
| `ValidateResponse.java` — Javadoc desatualizado | **DÉBITO DE DOCUMENTAÇÃO** | Classe ainda contém o comentário `"Valor fixo PENDING_JWT até a História 6..."`. Com JWT real implementado, esse Javadoc é incorreto e confuso |
| `SessionNotFoundException` | APROVADO | Lança exceção com `sessionId` |
| `SpoofingDetectedException` | APROVADO | |
| `FaceMismatchException` | APROVADO | Inclui `similarity` e `threshold` na mensagem interna |
| `PoorQualityImageException` | APROVADO | |
| `LivenessValidationExceptionHandler` — HTTP 422 para todos os erros | APROVADO | Mapeamento correto para todos os quatro tipos de exceção |
| Mensagens de erro ao cliente — alinhamento com `openapi.yaml` | **CORRIGIDO** | Todas as mensagens do `LivenessValidationExceptionHandler` alinhadas com o contrato exato do `openapi.yaml`: `SESSION_NOT_FOUND`, `SPOOFING_DETECTED`, `FACE_MISMATCH` e `POOR_QUALITY` |

---

### História 5 — Motor de Device Trust (Fast Track)

**Veredito: APROVADO**

| Subtask | Status | Observação |
|---------|--------|------------|
| Verificação de trust **antes** de chamar S3 ou Rekognition | APROVADO | Posição correta no fluxo de `validateBiometrics()` |
| Chave Redis `device:trust:{deviceId}:{cpf}` | APROVADO | CPF em plain text (não hasheado), conforme spec |
| Fast Track não aciona `S3StorageAdapter` nem `AwsRekognitionAdapter` | APROVADO | Retorno imediato quando `isTrusted == true` |
| Registro de trust ao final do Cenário A e Cenário B | APROVADO | `redisTemplate.opsForValue().set(trustKey, "trusted", Duration.ofDays(30))` em ambos os ramos |
| TTL exatamente 30 dias | APROVADO | `Duration.ofDays(30)` — equivale a 2592000 segundos |
| Fast Track emite JWT com `ACTIVE_BY_CACHE` | APROVADO | `jwtTokenProvider.generate(..., TokenState.ACTIVE_BY_CACHE)` |
| `Boolean.TRUE.equals(redisTemplate.hasKey(...))` — null-safe | APROVADO | Proteção correta contra NPE quando Redis retorna `null` |
| Renovação de trust mesmo que já exista | APROVADO | `set()` sobrescreve e renova o TTL em qualquer caso |

---

### História 6 — Emissão do Token de Validação Final (JWT)

**Veredito: APROVADO**

| Subtask | Status | Observação |
|---------|--------|------------|
| Dependências JJWT 0.12.6 no `pom.xml` | APROVADO | `jjwt-api`, `jjwt-impl` (runtime), `jjwt-jackson` (runtime) |
| `TokenState` enum — valores corretos | APROVADO | `ACTIVE_VERIFIED`, `ACTIVE_BY_CACHE` |
| `JwtTokenProvider` no pacote `security` | APROVADO | |
| Claim `iss` = `"plankton-liveness"` | APROVADO | `.issuer("plankton-liveness")` |
| Claim `sub` = CPF | APROVADO | `.subject(cpf)` |
| Claims `deviceId`, `origin`, `state` presentes | APROVADO | `.claim("deviceId", ...)`, `.claim("origin", ...)`, `.claim("state", state.name())` |
| `iat` e `exp` gerados automaticamente | APROVADO | `Instant.now()` + `Duration.ofSeconds(ttlSeconds)` |
| Algoritmo HS256 via JJWT 0.12.x | APROVADO | `Keys.hmacShaKeyFor()` + `.signWith(key)` — JJWT infere HS256 automaticamente para chave de 256 bits |
| Secret via `${jwt.secret}`, nunca hardcoded | APROVADO | `@Value("${jwt.secret}")` |
| TTL via `${jwt.ttl-seconds}` | APROVADO | `@Value("${jwt.ttl-seconds}")` — 300 segundos por padrão no `application.yml` |
| Validação mínima de 32 bytes no secret | APROVADO | `@PostConstruct` com `Assert.isTrue(keyBytes.length >= 32, ...)` — previne chaves fracas |
| `LivenessValidationService` integrado ao `JwtTokenProvider` | APROVADO | Injeção via `@RequiredArgsConstructor`, chamado nos dois caminhos (Fast Track e IA) |

---

## Requisitos Funcionais e Não Funcionais

| Requisito | Status | Observação |
|-----------|--------|------------|
| RF01 — Inicialização de sessão com CPF + DeviceID | APROVADO | `POST /api/v1/liveness/sessions` funcional |
| RF02 — Validação apenas em sessões ativas (TTL) | APROVADO PARCIAL | Sessão verificada no Redis; não é invalidada após uso (débito técnico) |
| RF03 — Device Trust / Fast Track (30 dias) | APROVADO | Lógica completa em `LivenessValidationService` |
| RF04 — Orquestração Cenário A (Cadastro) e B (Re-validação) | APROVADO | Fluxo correto: detectFaces → upload (A) e detectFaces → compareFaces → upload (B) |
| RF05 — Versionamento de biometria via S3 nativo | APROVADO | Bucket configurado com versionamento; adapter sobrescreve `reference.png` |
| RF06 — Emissão de JWT assinado | APROVADO | JWT com claims completos e assinatura HS256 |
| RF07 — Ofuscação de CPF via HMAC-SHA256 na chave S3 | APROVADO (com desvio de spec documentado) | HMAC-SHA256 implementado; spec dizia SHA-256 simples |
| RNF01 — Latência: IA < 2s, Fast Track < 100ms | NÃO VERIFICÁVEL | Sem benchmark automatizado. Fast Track é puramente Redis (esperado < 100ms). Fluxo IA depende de latência AWS Rekognition |
| RNF02 — Sessão em Redis com TTL estrito de 5 minutos | APROVADO | `Duration.ofSeconds(300)` |
| RNF03 — Imagem via `multipart/form-data` | APROVADO | `@RequestParam("file") MultipartFile file` |
| RNF04 — S3 e Rekognition em AWS real; Redis local | APROVADO | `DefaultCredentialsProvider`, sem LocalStack |

---

## Débitos Técnicos

1. ~~**Sessão não invalidada após uso (`LivenessValidationService`)**~~ **CORRIGIDO** — `redisTemplate.delete(sessionId)` adicionado ao final dos três caminhos de execução: Fast Track, Cenário A e Cenário B. O `sessionId` agora é de uso único.

2. **Desvio de algoritmo: HMAC-SHA256 vs SHA-256 simples**
   A spec (H3) define `SHA-256 de (cpf + secret)`. O código implementa HMAC-SHA256. A discrepância é uma melhoria de segurança, mas o spec deve ser atualizado ou a decisão deve ser registrada formalmente como ADR (Architecture Decision Record), pois qualquer outro serviço que precise replicar o hash seguindo o spec gerará um hash diferente.

3. **`ValidateResponse.java` — Javadoc desatualizado**
   O campo `verificationToken` ainda tem o comentário `"Valor fixo PENDING_JWT até a História 6 implementar a emissão de JWT real."` A História 6 está completa. O Javadoc deve ser atualizado para descrever o JWT real emitido.

4. **`application.yml` — `aws.region` hardcoded como `us-east-1`**
   O valor `us-east-1` está fixo no `application.yml`. Para ambientes multi-região ou staging em outra região, seria necessário alterar o arquivo. Considerar externalizar para `${AWS_REGION:us-east-1}` seguindo o mesmo padrão das outras variáveis.

5. **`terraform.tfstate` versionado no repositório**
   Arquivos `terraform.tfstate` e `terraform.tfstate.backup` estão rastreados pelo git. Esses arquivos contêm IDs de recursos AWS (ARNs, IDs de policies, IDs do bucket). Devem ser adicionados ao `.gitignore`. Para times, adotar backend remoto (S3 + DynamoDB para lock de estado).

---

## Riscos para Produção

1. ~~**Reutilização de `sessionId` (uso único não garantido)**~~ **CORRIGIDO** — Sessão deletada do Redis após consumo nos três caminhos de execução. `sessionId` agora é de uso único.

2. **Content-Type fixo como `image/png` no S3StorageAdapter**
   O `upload()` sempre define `contentType("image/png")` independente do formato enviado. O spec aceita `.png` e `.jpeg`. Se um JPEG for enviado, será armazenado com `Content-Type: image/png` no S3. O Rekognition consegue processar ambos os formatos independente do metadata, mas o metadata do objeto S3 ficará incorreto. Para produção, inferir o tipo via `file.getContentType()` ou `Files.probeContentType()`.

3. **Ausência de validação de tamanho e tipo de arquivo no endpoint de validação**
   O `application.yml` define `max-file-size: 10MB`. Não há validação explícita de que o arquivo seja uma imagem (MIME type check). Um cliente pode enviar um PDF ou arquivo binário qualquer — o Rekognition retornará erro, mas a exceção não será tratada como `INVALID_REQUEST` e pode vazar como erro 500 não tratado se o SDK lançar uma exceção não mapeada nos handlers.

4. **Exceções não mapeadas do AWS SDK podem retornar HTTP 500**
   `S3StorageAdapter.exists()` propaga exceções AWS diferentes de `NoSuchKeyException`. `AwsRekognitionAdapter` não trata `InvalidImageException`, `AccessDeniedException` ou erros de throttling do Rekognition. Qualquer exceção não capturada pelos `@ControllerAdvice` existentes resultará em HTTP 500 com possível stack trace exposto (dependendo da configuração do Spring Boot). Adicionar handler de `Exception` genérico no `LivenessValidationExceptionHandler`.

5. **CPF em plain text no log de `LivenessValidationService`**
   A linha `log.info("Validating biometrics. sessionId={}, deviceId={}", sessionId, deviceId)` não expõe o CPF. Porém `log.info("Session created. sessionId={}", sessionId)` em `LivenessSessionService` também é seguro. **Verificado: CPF não aparece em nenhum `log.*` call.** Sem risco. Incluído aqui apenas para confirmação.

6. **Secret JWT com menos de 256 bits (32 bytes) é bloqueado em runtime, mas não em startup de testes**
   A validação `@PostConstruct` no `JwtTokenProvider` protege o runtime, mas se `${JWT_SECRET}` não estiver definida nos testes unitários, o contexto Spring não sobe. A classe `CryptoUtilsTest` usa `ReflectionTestUtils` e não instancia o contexto — correto. Futuros testes que instanciem `JwtTokenProvider` precisarão fornecer o secret de teste via `ReflectionTestUtils` ou `@TestPropertySource`.

---

## Observações de Qualidade

1. **Dois `@RestControllerAdvice` escopados ao mesmo controller**
   `LivenessSessionExceptionHandler` e `LivenessValidationExceptionHandler` ambos usam `assignableTypes = LivenessSessionController.class`. Como ambos os endpoints (`/sessions` e `/validate`) estão no mesmo controller, isso é funcional. Porém, o escopo por `assignableTypes` em um único controller que serve dois domínios (criação vs validação) pode dificultar manutenção. Considerar separar em `LivenessSessionController` e `LivenessValidationController`, cada um com seu handler dedicado.

2. **`S3StorageAdapter.upload()` — content-type inflexível**
   `contentType("image/png")` fixo. Correto para spec atual, mas frágil. Considerar receber o `ContentType` como parâmetro ou inferir do conteúdo para suportar JPEG nativamente.

3. **`AwsRekognitionAdapter` — `bucket` injetado via `@Value`**
   O adapter de Rekognition precisa do nome do bucket para montar o `S3Object` pointer. Embora funcional, cria acoplamento entre o adapter de Rekognition e a configuração de S3. Uma alternativa seria receber o nome do bucket como parâmetro em `compareFaces(String bucket, String key, byte[] target)`, tornando o método mais testável e o adapter menos acoplado ao ambiente.

4. **`CryptoUtils` — `Mac.getInstance()` é chamado a cada invocação**
   `Mac.getInstance("HmacSHA256")` cria uma nova instância a cada chamada ao `hash()`. Para alto volume de requisições, considerar pré-instanciar e reutilizar via `ThreadLocal<Mac>` ou reconstruir o `SecretKeySpec` uma única vez em `@PostConstruct`. Impacto baixo no volume esperado desta aplicação.

5. **`LivenessSessionService` — ausência de tratamento de falha de Redis**
   Se o Redis estiver indisponível durante `createSession()`, a exceção será propagada como erro 500 sem mensagem amigável. Considerar capturar `RedisConnectionFailureException` e retornar `SessionInvalidRequestException` com mensagem adequada.

6. **`application.yml` — `rekognition.image-quality.min-brightness` e `min-sharpness` não estão na spec**
   Os thresholds de qualidade de imagem foram adicionados ao `application.yml` e ao adapter como melhoria não especificada. São valores positivos e configuraveis (40.0 por padrão). Recomenda-se adicionar comentário no YAML documentando a escala (0-100) e o impacto de cada threshold.

7. **Testes unitários — cobertura parcial**
   Apenas `CryptoUtilsTest` está implementado. Faltam testes para `JwtTokenProvider` e `LivenessValidationService` (História 7 — fora do escopo desta revisão, mas registrado como débito de qualidade pendente).

---

## Veredito Final

**APROVADO COM RESSALVAS MENORES**

A implementação das Histórias 1 a 6 está tecnicamente sólida e funcionalmente correta em todos os fluxos principais. O contrato de privacidade de PII é respeitado (CPF nunca aparece em logs ou chaves S3; HMAC-SHA256 com secret externo é criptograficamente adequado). A integração com AWS SDK v2 segue as melhores práticas: `DefaultCredentialsProvider`, S3Object pointer no Rekognition, sem download de referências biométricas no microsserviço. O JWT é gerado com todos os claims especificados, assinatura HS256 e TTL configurável.

Os dois itens bloqueantes identificados na revisão inicial foram corrigidos: **(a)** sessão deletada do Redis após uso nos três caminhos (Fast Track, Cenário A e Cenário B), garantindo que cada `sessionId` seja de uso único; **(b)** mensagens de erro alinhadas com o contrato exato do `openapi.yaml` em todos os handlers. Os débitos remanescentes são de documentação, qualidade e resiliência, e não bloqueiam os testes funcionais das Histórias 7 e 8.
