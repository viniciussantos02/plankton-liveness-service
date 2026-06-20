# PRD — plankton-liveness-service

## 1. Visão do Produto

Microsserviço Java Spring Boot responsável pela orquestração do fluxo de biometria facial e antifraude (**Facial Liveness** e **Face Match**) utilizando AWS Rekognition e S3. O serviço garante que somente pessoas reais e autenticadas possam completar fluxos sensíveis, como onboarding e transações financeiras.

---

## 2. Problema que Resolve

Fluxos digitais que exigem identidade verificada sofrem com:
- Ataques de spoofing (fotos estáticas, vídeos, máscaras)
- Fraudes de identidade (CPF de terceiros com foto diferente)
- Latência elevada por chamadas redundantes a APIs de IA

Este serviço resolve os três problemas com um fluxo orquestrado de Liveness + Face Match + Device Trust cache.

---

## 3. Objetivos (Goals)

| # | Objetivo | Métrica de Sucesso |
|---|----------|--------------------|
| G1 | Detectar spoofing com alta precisão | Confidence threshold ≥ 90% no Rekognition |
| G2 | Reduzir chamadas à IA para dispositivos recorrentes | Fast Track ativo para devices com trust nos últimos 30 dias |
| G3 | Proteger PII em todos os storages | CPF nunca salvo em plain text (hash SHA-256 + salt) |
| G4 | Latência aceitável | ≤ 2s com IA, ≤ 100ms via Fast Track |

---

## 4. Requisitos Funcionais

| ID | Requisito |
|----|-----------|
| RF01 | Criar sessão de liveness temporária atrelada a CPF e Device ID com TTL estrito de 5 minutos |
| RF02 | Validar biometria somente em sessões ativas previamente criadas |
| RF03 | Verificar Device Trust no Redis antes de chamar IA (Fast Track) |
| RF04-A | Cenário de cadastro: sem foto de referência no S3 → detectFaces → salva referência |
| RF04-B | Cenário de re-validação: com foto no S3 → detectFaces + compareFaces via S3Object pointer |
| RF05 | Sobrescrever foto de referência em re-validações bem-sucedidas (versionamento S3 nativo) |
| RF06 | Emitir JWT assinado após qualquer aprovação (cache ou IA) |
| RF07 | Ofuscar CPF com SHA-256 + salt antes de usar como path no S3 |

---

## 5. Requisitos Não Funcionais

| ID | Requisito |
|----|-----------|
| RNF01 | Latência ≤ 2s para fluxos com IA; ≤ 100ms para Fast Track |
| RNF02 | Sessões em Redis com TTL estrito de 5 minutos |
| RNF03 | Imagem transmitida via `multipart/form-data` (binário, sem base64) |
| RNF04 | S3 e Rekognition em contas AWS reais; Redis Dockerizado localmente |

---

## 6. Fluxo de Validação (Ordem Obrigatória)

```
[POST /validate recebido (multipart/form-data)]
                      │
                      ▼
1. sessionId existe no Redis?
          ├── NÃO ──► [HTTP 422] SESSION_NOT_FOUND
          └── SIM (recupera cpf, deviceId da sessão)
                  │
                  ▼
         2. device:trust:{deviceId}:{cpf} existe no Redis?
                  ├── SIM ──► [FAST TRACK] JWT com ACTIVE_BY_CACHE
                  └── NÃO (extrai byte[] do multipart)
                          │
                          ▼
                 3. profiles/{hash_cpf}/reference.png existe no S3?
                          ├── NÃO ──► [CENÁRIO A: CADASTRO]
                          │             detectFaces(bytes)
                          │             upload para S3
                          │             registra device trust (30d)
                          │             JWT com ACTIVE_VERIFIED
                          └── SIM ──► [CENÁRIO B: RE-VALIDAÇÃO]
                                        detectFaces(bytes)
                                        compareFaces(S3 pointer, bytes)
                                        sobrescreve no S3
                                        registra device trust (30d)
                                        JWT com ACTIVE_VERIFIED
```

---

## 7. Contratos de API

Fonte de verdade: [`openapi.yaml`](./openapi.yaml). Nunca implementar endpoints fora do spec.

### POST /api/v1/liveness/sessions
- Header obrigatório: `X-Device-ID`
- Body: `{ "cpf": "11111111111", "origin": "bank123" }`
- Sucesso: `201` com `{ sessionId, status: INITIALIZED, ttlInSeconds: 300 }`
- Erro: `400` com `{ status: REJECTED, reason: INVALID_REQUEST, message }`

### POST /api/v1/liveness/sessions/{sessionId}/validate
- Body: `multipart/form-data` com campo `file` (png ou jpeg)
- Header: `Authorization: Bearer <JWT>`
- Sucesso: `200` com `{ status: VALIDATED, verificationToken }`
- Erros: `422` com `{ status: REJECTED, reason, message }`
  - `SESSION_NOT_FOUND`
  - `SPOOFING_DETECTED`
  - `FACE_MISMATCH`
  - `POOR_QUALITY`

---

## 8. Modelo de Dados

### Redis — Sessão
- Chave: `{sessionId}` (UUID)
- TTL: 300 segundos (5 minutos)
- Valor: `{ cpf, deviceId, origin, status }`

### Redis — Device Trust
- Chave: `device:trust:{deviceId}:{cpf}`
- TTL: 2592000 segundos (30 dias)
- Valor: qualquer (flag de existência)

### S3 — Foto de Referência
- Path: `profiles/{sha256(cpf + secret)}/reference.png`
- Bucket: `plankton-liveness-biometrics`
- Versionamento: ativo (sobrescrição mantém histórico)

---

## 9. Regras de Segurança Críticas

1. **CPF nunca em plain text** — sempre `CryptoUtils.hash(cpf)` antes de qualquer storage
2. **Sem download da referência S3** — compareFaces usa `S3Object pointer` (`Image.builder().s3Object(...)`)
3. **Secrets via env vars** — `${CPF_HASH_SECRET}` e `${JWT_SECRET}` no `application.yml`
4. **Exceções sem stack trace** — respostas de erro expostas ao cliente devem ser opacas
5. **Sessão TTL estrito** — Redis com expiração de 5 min; não renovar em nenhuma condição

---

## 10. Thresholds de Confiança AWS Rekognition

| Operação | Threshold | Configuração |
|----------|-----------|--------------|
| detectFaces (Liveness) | ≥ 90.0% | `rekognition.liveness.confidence-threshold` |
| compareFaces (Face Match) | ≥ 90.0% | `rekognition.face-match.similarity-threshold` |

---

## 11. Estrutura de Pacotes

```
src/main/java/com/plankton/liveness/
├── config/          AwsConfig, RedisConfig
├── controller/      LivenessSessionController
├── service/         LivenessSessionService, LivenessValidationService
├── adapter/         S3StorageAdapter, AwsRekognitionAdapter
├── model/           LivenessSession
├── dto/
│   ├── request/     CreateSessionRequest
│   └── response/    CreateSessionResponse, ValidateResponse, ErrorResponse
├── enums/           SessionStatus, RejectionReason, TokenState
├── exception/       SessionInvalidRequestException, SessionNotFoundException,
│                    SpoofingDetectedException, FaceMismatchException, PoorQualityImageException
├── handler/         LivenessSessionExceptionHandler, LivenessValidationExceptionHandler
├── security/        JwtTokenProvider
└── util/            CryptoUtils
```

---

## 12. Ordem de Implementação das Histórias

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

Specs detalhados de cada história em [`specs/`](./specs/).
