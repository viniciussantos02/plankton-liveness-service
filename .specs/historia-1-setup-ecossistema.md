# História 1 — Setup do Ecossistema e Infraestrutura Real AWS

## Objetivo
Configurar o projeto base em Java Spring Boot e a infraestrutura na nuvem AWS via Terraform, mantendo apenas o cache local (Redis Dockerizado).

## Dependências
Nenhuma. Esta é a história inicial.

## Entregáveis

### 1. Projeto Spring Boot
- Inicializado via Spring Initializr com Java 21 LTS
- Group: `com.plankton`, Artifact: `liveness`
- Packaging: `jar`

### 2. `pom.xml` — Dependências obrigatórias

| Dependência | Finalidade |
|-------------|------------|
| `spring-boot-starter-web` | REST API |
| `spring-boot-starter-data-redis` | Cache Redis |
| `lombok` | Redução de boilerplate |
| `software.amazon.awssdk:s3` | AWS SDK v2 S3 |
| `software.amazon.awssdk:rekognition` | AWS SDK v2 Rekognition |
| `spring-boot-starter-validation` | Jakarta Bean Validation |

### 3. `docker-compose.yml`
Apenas dois serviços:
- **Redis** na porta padrão `6379`
- **RedisInsight** (interface visual) na porta `8001`

Não usar LocalStack. Não adicionar nenhum mock de AWS.

### 4. Terraform — `main.tf` e `variables.tf`
- Provedor AWS apontando para região `us-east-1`
- Usar credenciais da cadeia padrão (`DefaultCredentialsProvider`)

### 5. Terraform — Bucket S3 `plankton-liveness-biometrics`
- Versionamento: **habilitado**
- Criptografia server-side: **habilitada** (SSE-S3 ou SSE-KMS)
- Acesso público: **bloqueado**

### 6. IAM — Políticas mínimas necessárias
As credenciais locais devem ter permissão para:

**S3:**
- `s3:PutObject` — upload de fotos de referência
- `s3:GetObject` — leitura (usada pelo Rekognition via S3 pointer, não pelo serviço)
- `s3:HeadObject` — verificar existência da referência

**Rekognition:**
- `rekognition:DetectFaces`
- `rekognition:CompareFaces`

### 7. `application.yml` — estrutura inicial

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

Nunca fazer hardcode dos valores de `CPF_HASH_SECRET` e `JWT_SECRET`.

## Acceptance Criteria

- [ ] `mvn spring-boot:run` sobe a aplicação sem erros
- [ ] `docker-compose up -d` sobe Redis e RedisInsight sem erros
- [ ] `terraform plan` não apresenta erros de configuração
- [ ] Bucket S3 `plankton-liveness-biometrics` existe na conta AWS com versionamento ativo
- [ ] `application.yml` não contém secrets em plain text
- [ ] Dependências do `pom.xml` cobrem toda a stack necessária (S3, Rekognition, Redis, Validation, Lombok)

## O que NÃO deve ser feito
- Não usar LocalStack ou mocks de AWS
- Não adicionar dependências além das listadas acima sem aprovação
- Não hardcodar credenciais AWS no código ou no `application.yml`
- Não criar código de negócio nesta história — apenas infraestrutura e setup
