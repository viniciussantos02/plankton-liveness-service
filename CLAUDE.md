# plankton-liveness-service — CLAUDE.md

Este arquivo é a fonte de verdade para qualquer agente que trabalhar neste projeto.
Leia-o integralmente antes de escrever qualquer código.

---

## 1. Visão Geral

Microsserviço Java Spring Boot responsável pela orquestração do fluxo de biometria facial e antifraude (**Facial Liveness** e **Face Match**) utilizando AWS Rekognition e S3.

- **Spec de referência:** [`openapi.yaml`](./openapi.yaml) — fonte de verdade dos contratos de API
- **Plano de execução:** [`plankton-liveness-service-execution-plan.md`](./plankton-liveness-service-execution-plan.md) — backlog e requisitos detalhados

---

## 2. Stack e Versões

| Tecnologia         | Versão        |
|--------------------|---------------|
| Java               | 21 LTS        |
| Spring Boot        | 3.x           |
| Build              | Maven         |
| Cache              | Redis (Docker)|
| Object Storage     | AWS S3        |
| AI / Biometria     | AWS Rekognition|
| Testes unitários   | JUnit 5 + Mockito |
| JWT                | io.jsonwebtoken (JJWT) |

---

## 3. Estrutura de Pacotes Esperada

```
src/main/java/com/plankton/liveness/
├── config/
│   ├── AwsConfig.java            # Beans: S3Client, RekognitionClient
│   └── RedisConfig.java          # Bean: RedisTemplate
├── controller/
│   └── LivenessSessionController.java
├── service/
│   ├── LivenessSessionService.java    # Criação de sessão
│   └── LivenessValidationService.java # Orquestração do fluxo de validação
├── adapter/
│   ├── S3StorageAdapter.java
│   └── AwsRekognitionAdapter.java
├── model/
│   └── LivenessSession.java
├── dto/
│   ├── request/
│   │   └── CreateSessionRequest.java
│   └── response/
│       ├── CreateSessionResponse.java
│       ├── ValidateResponse.java
│       └── ErrorResponse.java
├── enums/
│   ├── SessionStatus.java       # INITIALIZED, VALIDATED, REJECTED
│   ├── RejectionReason.java     # SPOOFING_DETECTED, FACE_MISMATCH, POOR_QUALITY, SESSION_NOT_FOUND, INVALID_REQUEST
│   └── TokenState.java          # ACTIVE_VERIFIED, ACTIVE_BY_CACHE
├── exception/
│   ├── SessionInvalidRequestException.java
│   ├── SessionNotFoundException.java
│   ├── SpoofingDetectedException.java
│   ├── FaceMismatchException.java
│   └── PoorQualityImageException.java
├── handler/
│   ├── LivenessSessionExceptionHandler.java   # @ControllerAdvice para erros de sessão
│   └── LivenessValidationExceptionHandler.java # @ControllerAdvice para erros de validação
├── security/
│   └── JwtTokenProvider.java
└── util/
    └── CryptoUtils.java          # SHA-256 com salt
```

---

## 4. Contratos de API

**Fonte de verdade: [`openapi.yaml`](./openapi.yaml)**

Nunca implemente endpoints que não estejam definidos no `openapi.yaml`.
Se precisar adicionar ou alterar um contrato, atualize o `openapi.yaml` primeiro.

### Endpoints

| Método | Path | Descrição |
|--------|------|-----------|
| POST | `/api/v1/liveness/sessions` | Cria sessão de liveness (TTL 5 min) |
| POST | `/api/v1/liveness/sessions/{sessionId}/validate` | Valida biometria facial |

---

## 5. Regras de Negócio Críticas

### Fluxo de Validação (ordem obrigatória)

1. Verificar se `sessionId` existe no Redis → 422 `SESSION_NOT_FOUND` se não existir
2. Verificar se `deviceId + cpf` está em `device:trust:{deviceId}:{cpf}` no Redis → aprovar direto (Fast Track)
3. Verificar se `profiles/{hash_cpf}/reference.png` existe no S3
   - **Não existe (Cenário A):** detectFaces → salvar no S3 → registrar trust
   - **Existe (Cenário B):** detectFaces → compareFaces (S3 pointer) → sobrescrever no S3 → registrar trust
4. Emitir JWT assinado

### Device Trust
- Chave Redis: `device:trust:{deviceId}:{cpf}`
- TTL: 30 dias
- Aprovação por cache emite token com `TokenState.ACTIVE_BY_CACHE`
- Aprovação por IA emite token com `TokenState.ACTIVE_VERIFIED`

### Ofuscação de CPF no S3
- Nunca salve o CPF diretamente como path no S3
- Sempre aplique `CryptoUtils.hash(cpf)` — SHA-256 + secret configurado em `application.yml`
- Path resultante: `profiles/{hash}/reference.png`

### Sessão
- TTL estrito: **5 minutos** no Redis
- Dados guardados na sessão: `cpf`, `deviceId`, `origin`, `status`

### Rekognition — otimização de rede
- `detectFaces`: sempre via bytes em memória (imagem recém-enviada)
- `compareFaces`: imagem de referência via `S3Object pointer` (nunca fazer download), imagem atual via bytes

---

## 6. Configurações de Ambiente (`application.yml`)

```yaml
aws:
  region: us-east-1
  s3:
    bucket: plankton-liveness-biometrics
rekognition:
  liveness:
    confidence-threshold: 90.0
  face-match:
    similarity-threshold: 90.0
crypto:
  cpf-hash-secret: ${CPF_HASH_SECRET}
jwt:
  secret: ${JWT_SECRET}
  ttl-seconds: 300
```

Nunca faça hardcode de secrets. Sempre use variáveis de ambiente.

---

## 7. Regras para Agentes

### Ao implementar uma história

1. Leia a história correspondente no `plankton-liveness-service-execution-plan.md`
2. Confirme os contratos no `openapi.yaml` antes de criar DTOs ou controllers
3. Siga a estrutura de pacotes da seção 3 deste arquivo
4. Não crie abstrações além do que a história exige
5. Não implemente histórias fora de ordem sem justificativa explícita

### Ao escrever testes

- Testes unitários: mockar `S3StorageAdapter` e `AwsRekognitionAdapter` — nunca chamar AWS real
- Testes de integração (História 8): conectar à AWS real com credenciais de ambiente
- Cobertura obrigatória: todos os caminhos de exceção definidos nos enums `RejectionReason`

### O que nunca fazer

- Não baixar imagem de referência do S3 para comparar faces — use S3Object pointer
- Não salvar CPF em plain text em nenhum storage
- Não ignorar o TTL de 5 minutos da sessão
- Não criar endpoints fora do `openapi.yaml`
- Não adicionar dependências sem atualizar o `pom.xml`

---

## 8. Infraestrutura Local

```bash
# Subir Redis local
docker-compose up -d

# Verificar cache
# Interface visual disponível via docker-compose (ex: RedisInsight na porta 8001)
```

AWS (S3 e Rekognition) sempre apontam para contas reais. Não usar LocalStack.

---

## 9. Ordem de Implementação das Histórias

| # | História | Depende de |
|---|----------|------------|
| 1 | Setup do Ecossistema e Infra AWS | — |
| 2 | Criação de Sessão e Persistência Redis | História 1 |
| 3 | Camada S3 (hash + upload + exists) | História 1 |
| 4 | Integração AWS Rekognition + orquestração | Histórias 2 e 3 |
| 5 | Device Trust (Fast Track Redis) | História 4 |
| 6 | Emissão JWT | História 4 |
| 7 | Testes Unitários | Histórias 2–6 |
| 8 | Testes de Integração E2E | História 7 |
