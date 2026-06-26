# plankton-liveness-service

Microsserviço Java responsável pela orquestração do fluxo de biometria facial e antifraude (*Facial Liveness* e *Face Match*) utilizando infraestrutura real AWS. O SDD(Spec-Driven-Development) foi usado aqui para desenvolvimento como teste da abordagem, e foi usado o Claude Code para implementacao do SDD.

---

## Visão Geral

O serviço expõe dois endpoints principais:

1. **Criação de Sessão** — gera um `sessionId` temporário atrelado a um CPF e dispositivo
2. **Validação Biométrica** — recebe a selfie, executa liveness detection e face match, e retorna um JWT assinado

### Fluxo de Validação

```
POST /validate recebido (multipart/form-data)
          │
          ▼
1. sessionId existe no Redis? (TTL 5 min)
          ├── NÃO ──► HTTP 422 SESSION_NOT_FOUND
          └── SIM
                  │
                  ▼
         2. device:trust:{deviceId}:{cpf} existe no Redis? (TTL 30 dias)
                  ├── SIM ──► JWT com ACTIVE_BY_CACHE (Fast Track — sem S3/IA)
                  └── NÃO
                          │
                          ▼
                 3. profiles/{hmac_cpf}/reference.png existe no S3?
                          ├── NÃO ──► Cenário A: detectFaces → upload S3 → JWT ACTIVE_VERIFIED
                          └── SIM ──► Cenário B: detectFaces → compareFaces → upload S3 → JWT ACTIVE_VERIFIED
```

---

## Stack Tecnológica

| Camada | Tecnologia |
|--------|-----------|
| Linguagem | Java 21 LTS |
| Framework | Spring Boot 3.3.4 |
| Cache / Sessões | Redis 7 (Docker local) |
| Armazenamento biométrico | AWS S3 |
| IA / Biometria | AWS Rekognition |
| Segurança | JWT HS256 (JJWT 0.12.6) |
| Infraestrutura | Terraform >= 1.5.0 |
| Build | Maven |

---

## Pré-requisitos

Certifique-se de ter instalado:

- **Java 21** (`java -version`)
- **Maven 3.8+** (`mvn -version`)
- **Docker** e **Docker Compose** (`docker -v`)
- **AWS CLI** configurado (`aws configure`)
- **Terraform 1.5+** (`terraform -version`) — somente para provisionamento inicial

---

## Configuração Inicial (primeira vez)

### 1. Clone o repositório

```bash
git clone https://github.com/viniciussantos02/plankton-liveness-service.git
cd plankton-liveness-service
```

### 2. Configure as credenciais AWS

O serviço usa a cadeia padrão de credenciais da AWS (`DefaultCredentialsProvider`). Configure via AWS CLI:

```bash
aws configure
```

Informe:
- **AWS Access Key ID**
- **AWS Secret Access Key**
- **Default region**: `us-east-1`
- **Default output format**: `json`

> As credenciais devem ter permissão de `s3:PutObject`, `s3:HeadObject`, `rekognition:DetectFaces` e `rekognition:CompareFaces`.

### 3. Provisione a infraestrutura AWS via Terraform

```bash
cd terraform
terraform init
terraform apply
```

Confirme com `yes` quando solicitado. Isso cria:
- Bucket S3 `plankton-liveness-biometrics` com versionamento e criptografia SSE-S3
- Usuário IAM `plankton_liveness_user` com políticas mínimas

> Execute este passo apenas uma vez. Após o provisionamento, a infraestrutura persiste na AWS.

```bash
cd ..  # volte para a raiz do projeto
```

### 4. Configure as variáveis de ambiente

As credenciais AWS já estão configuradas via `aws configure` (passo anterior) — o SDK as encontra automaticamente em `~/.aws/credentials`. As únicas variáveis que precisam ser definidas manualmente são os secrets da aplicação:

```bash
# Gere valores seguros com: openssl rand -hex 32
export CPF_HASH_SECRET=<string-aleatoria-minimo-32-caracteres>
export JWT_SECRET=<string-aleatoria-minimo-32-caracteres>
```

Opcionalmente, salve em um arquivo `.env` local (não comite este arquivo):

```bash
source .env
```

> **Importante:** `JWT_SECRET` deve ter no mínimo 32 bytes. Valores menores causam falha na inicialização da aplicação.

---

## Executando o Projeto

### 1. Suba o Redis e o RedisInsight

```bash
docker compose up -d
```

Verifique se os containers estão saudáveis:

```bash
docker ps
```

Você deve ver:
- `plankton-redis` — Redis na porta `6379`
- `plankton-redisinsight` — Interface visual na porta `8001`

### 2. Inicie a aplicação

```bash
mvn spring-boot:run
```

A aplicação sobe na porta `8080`. Aguarde a mensagem:

```
Started LivenessApplication in X.XXX seconds
```

---

## Testando os Endpoints

### Endpoint 1 — Criar Sessão

```bash
curl -X POST http://localhost:8080/api/v1/liveness/sessions \
  -H "Content-Type: application/json" \
  -H "X-Device-ID: meu-dispositivo-001" \
  -d '{
    "cpf": "12345678901",
    "origin": "bank123"
  }'
```

**Resposta esperada (HTTP 201):**
```json
{
  "sessionId": "6a7b8c9d-e1f2-3a4b-5c6d-7e8f9a0b1c2d",
  "status": "INITIALIZED",
  "ttlInSeconds": 300
}
```

### Endpoint 2 — Validar Biometria

Use o `sessionId` retornado acima:

```bash
curl -X POST http://localhost:8080/api/v1/liveness/sessions/{sessionId}/validate \
  -H "Authorization: Bearer token-qualquer" \
  -F "file=@/caminho/para/sua/foto.png"
```

**Resposta esperada (HTTP 200):**
```json
{
  "status": "VALIDATED",
  "verificationToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**Respostas de erro (HTTP 422):**

| Cenário | `reason` | `message` |
|---------|----------|-----------|
| Sessão expirada ou inválida | `SESSION_NOT_FOUND` | A sessão de liveness informada expirou ou não existe. |
| Tentativa de fraude | `SPOOFING_DETECTED` | A análise de biometria facial indicou uma possível tentativa de fraude. |
| Rosto diferente do cadastro | `FACE_MISMATCH` | O rosto enviado não corresponde ao cadastro existente. |
| Imagem de baixa qualidade | `POOR_QUALITY` | A análise de biometria facial indicou que a qualidade da imagem é insuficiente para validação, tente novamente. |

> **Atenção:** cada `sessionId` é de uso único. Após a validação (sucesso ou falha de biometria), a sessão é automaticamente removida do Redis.

---

## Visualizando o Cache (RedisInsight)

Acesse `http://localhost:8001` no navegador e conecte ao banco:

| Campo | Valor |
|-------|-------|
| Host | `plankton-redis` |
| Port | `6379` |
| Database Alias | `plankton-liveness` |

Na aba **Browser** você verá:
- Chaves de sessão (UUID) com TTL de 5 minutos
- Chaves `device:trust:{deviceId}:{cpf}` com TTL de 30 dias após primeira validação aprovada

---

## Estrutura do Projeto

```
src/main/java/com/plankton/liveness/
├── adapter/
│   ├── AwsRekognitionAdapter.java   # detectFaces e compareFaces
│   └── S3StorageAdapter.java        # exists() e upload()
├── config/
│   ├── AwsConfig.java               # S3Client e RekognitionClient
│   └── RedisConfig.java             # RedisTemplate com serialização JSON
├── controller/
│   └── LivenessSessionController.java
├── dto/
│   ├── request/CreateSessionRequest.java
│   └── response/
│       ├── CreateSessionResponse.java
│       ├── ValidateResponse.java
│       └── ErrorResponse.java
├── enums/
│   ├── SessionStatus.java           # INITIALIZED, VALIDATED, REJECTED
│   ├── RejectionReason.java         # SPOOFING_DETECTED, FACE_MISMATCH, ...
│   └── TokenState.java              # ACTIVE_VERIFIED, ACTIVE_BY_CACHE
├── exception/                       # Exceções de negócio customizadas
├── handler/                         # @ControllerAdvice para erros HTTP
├── model/
│   └── LivenessSession.java         # Modelo persistido no Redis
├── security/
│   └── JwtTokenProvider.java        # Geração JWT HS256
├── service/
│   ├── LivenessSessionService.java  # Criação de sessão
│   └── LivenessValidationService.java # Orquestração do fluxo completo
└── util/
    └── CryptoUtils.java             # HMAC-SHA256 para ofuscação do CPF
```

---

## Variáveis de Ambiente

| Variável | Obrigatória | Descrição |
|----------|-------------|-----------|
| `CPF_HASH_SECRET` | Sim | Salt para o HMAC-SHA256 do CPF usado na chave S3 |
| `JWT_SECRET` | Sim | Chave de assinatura dos tokens JWT (mínimo 32 bytes) |
| `REDIS_HOST` | Não | Host do Redis (padrão: `localhost`) |
| `REDIS_PORT` | Não | Porta do Redis (padrão: `6379`) |

> As credenciais AWS (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`) **não precisam ser definidas como variáveis de ambiente** — o SDK lê automaticamente o arquivo `~/.aws/credentials` configurado via `aws configure`.

---

## Configurações da Aplicação (`application.yml`)

| Propriedade | Padrão | Descrição |
|-------------|--------|-----------|
| `server.port` | `8080` | Porta HTTP da aplicação |
| `aws.region` | `us-east-1` | Região AWS |
| `aws.s3.bucket` | `plankton-liveness-biometrics` | Nome do bucket S3 |
| `rekognition.liveness.confidence-threshold` | `90.0` | Confiança mínima no detectFaces (0–100) |
| `rekognition.face-match.similarity-threshold` | `90.0` | Similaridade mínima no compareFaces (0–100) |
| `rekognition.image-quality.min-brightness` | `40.0` | Brilho mínimo da imagem (0–100) |
| `rekognition.image-quality.min-sharpness` | `40.0` | Nitidez mínima da imagem (0–100) |
| `jwt.ttl-seconds` | `300` | Tempo de vida do JWT em segundos |
| `spring.servlet.multipart.max-file-size` | `10MB` | Tamanho máximo do arquivo de imagem |

---

## Executando os Testes

### Testes Unitários

Não requerem AWS nem Redis. Rodam isolados com mocks.

```bash
mvn test
```

### Testes de Integração E2E (Ambiente Real AWS)

Conectam à AWS real (S3 e Rekognition) e ao Redis local. Requerem o ambiente completo configurado.

**Pré-requisitos:**
- Redis rodando localmente (`docker compose up -d`)
- Credenciais AWS configuradas (`aws configure` ou variáveis de ambiente)
- Imagens de rosto reais disponíveis em paths locais (não commitadas no repositório)

**Variáveis de ambiente obrigatórias:**

| Variável | Descrição |
|----------|-----------|
| `CPF_HASH_SECRET` | Salt para o HMAC-SHA256 do CPF |
| `JWT_SECRET` | Chave de assinatura JWT (mínimo 32 bytes) |
| `AWS_ACCESS_KEY_ID` | Access key AWS (ou via `~/.aws/credentials`) |
| `AWS_SECRET_ACCESS_KEY` | Secret key AWS (ou via `~/.aws/credentials`) |
| `TEST_FACE_IMAGE_PATH` | Path local de uma foto com rosto humano válido (Cenários A, B, D2) |
| `TEST_FACE_B_IMAGE_PATH` | Path local de um rosto **diferente** do anterior (Cenário D2) |

> Sem `TEST_FACE_IMAGE_PATH` e `TEST_FACE_B_IMAGE_PATH` definidos, os Cenários A, B e D2 são pulados (`SKIPPED`) — não falham. Para cobertura completa, essas variáveis são obrigatórias.

**Como rodar:**

```bash
# 1. Suba o Redis local
docker compose up -d

# 2. Exporte as variáveis
export CPF_HASH_SECRET=<secret>
export JWT_SECRET=<secret-minimo-32-bytes>
export TEST_FACE_IMAGE_PATH=/caminho/para/foto-com-rosto.png
export TEST_FACE_B_IMAGE_PATH=/caminho/para/foto-rosto-diferente.png

# 3. Execute os testes de integração
mvn verify -Pintegration
```

**Cenários cobertos:**

| Cenário | Descrição | Resultado esperado |
|---------|-----------|-------------------|
| A | Primeiro acesso — `detectFaces` + upload S3 | JWT `ACTIVE_VERIFIED` |
| B | Re-validação — `compareFaces` via S3 pointer + nova versão no bucket | JWT `ACTIVE_VERIFIED` |
| C | Fast Track — `device:trust` ativa no Redis | JWT `ACTIVE_BY_CACHE` em ≤ 100ms |
| D1 | Imagem sem rosto | HTTP 422 `SPOOFING_DETECTED`/`POOR_QUALITY` |
| D2 | Rosto diferente da referência cadastrada | HTTP 422 `FACE_MISMATCH` |
| D3 | Sessão expirada ou inexistente | HTTP 422 `SESSION_NOT_FOUND` |

---

## Parando o Ambiente

```bash
# Parar a aplicação: Ctrl+C no terminal onde está rodando

# Parar os containers Docker
docker compose down

# Para remover os volumes (apaga dados do Redis)
docker compose down -v
```

---

## Destruindo a Infraestrutura AWS

> Execute apenas quando quiser remover todos os recursos provisionados.

```bash
cd terraform
terraform destroy
```
