# História 8 — Testes de Integração E2E (Ambiente Real AWS)

## Objetivo
Realizar testes ponta a ponta conectados à infraestrutura real da AWS para certificar que credenciais, políticas IAM, ciclo de vida do S3 e motores de IA funcionam de forma integrada.

## Dependências
- História 7 concluída (todos os testes unitários passando)
- Infraestrutura AWS provisionada (História 1)
- Credenciais AWS disponíveis no ambiente de execução

## Premissas
- Redis local rodando via `docker-compose up -d`
- Variáveis de ambiente definidas: `CPF_HASH_SECRET`, `JWT_SECRET`
- Credenciais AWS com as permissões IAM da História 1
- Bucket S3 `plankton-liveness-biometrics` existente e acessível
- Rekognition disponível na região `us-east-1`

## Estratégia de Teste

Usar `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` para simular chamadas HTTP reais ao serviço completo, sem mocks de adapters AWS.

```
src/test/java/com/plankton/liveness/integration/
└── LivenessFlowIntegrationTest.java
```

## Cenários de Integração

### Cenário A — Primeiro Acesso / Cadastro (Happy Path)

**Objetivo:** Validar fluxo completo de um CPF sem referência no S3.

**Pré-condição:**
- Garantir que `profiles/{hash(cpf_teste_A)}/reference.png` **não existe** no S3
- Não existe device trust para o par `deviceId + cpf_teste_A`

**Passos:**
1. `POST /api/v1/liveness/sessions` com CPF de teste → obter `sessionId`
2. `POST /api/v1/liveness/sessions/{sessionId}/validate` com imagem válida de rosto real

**Verificações:**
- [ ] Response `200` com `status: VALIDATED`
- [ ] `verificationToken` é um JWT válido com `state: ACTIVE_VERIFIED`
- [ ] JWT contém claims: `iss`, `sub`, `deviceId`, `origin`, `state`
- [ ] Objeto `profiles/{hash(cpf_teste_A)}/reference.png` criado no S3 real
- [ ] Chave `device:trust:{deviceId}:{cpf_teste_A}` criada no Redis com TTL ~30 dias

---

### Cenário B — Re-validação com Comparação Facial via S3 Pointer

**Objetivo:** Validar que o Rekognition lê a referência diretamente do S3 sem download.

**Pré-condição:**
- `profiles/{hash(cpf_teste_B)}/reference.png` **já existe** no S3 (executar Cenário A antes com mesmo CPF)
- Não existe device trust (limpar Redis antes do teste)

**Passos:**
1. `POST /api/v1/liveness/sessions` com `cpf_teste_B`
2. `POST /validate` com imagem do mesmo rosto

**Verificações:**
- [ ] Response `200` com `status: VALIDATED`
- [ ] `verificationToken` com `state: ACTIVE_VERIFIED`
- [ ] Nova versão do objeto criada no S3 (versionamento — bucket deve ter 2+ versões)
- [ ] Chave `device:trust:{deviceId}:{cpf_teste_B}` criada/renovada no Redis

---

### Cenário C — Fast Track via Device Trust (Cache)

**Objetivo:** Validar que o bypass de IA funciona quando o device trust está ativo.

**Pré-condição:**
- Chave `device:trust:{deviceId}:{cpf_teste_C}` **manualmente inserida** no Redis antes do teste

**Passos:**
1. `POST /api/v1/liveness/sessions` com `cpf_teste_C`
2. `POST /validate` com qualquer imagem (ou imagem inválida — deve aprovar mesmo assim)

**Verificações:**
- [ ] Response `200` com `status: VALIDATED`
- [ ] `verificationToken` com `state: ACTIVE_BY_CACHE`
- [ ] Nenhum objeto novo criado no S3 durante o teste (confirmar via HeadObject ou listar versões)
- [ ] Latência da chamada de validação ≤ 100ms (Fast Track)

---

### Cenário D — Spoofing / Divergência Biométrica

**Objetivo:** Validar que o Rekognition rejeita imagens inválidas ou rostos diferentes.

**Sub-cenário D1 — Imagem sem rosto:**
1. `POST /api/v1/liveness/sessions`
2. `POST /validate` com imagem sem rosto (ex: paisagem, objeto)
- [ ] Response `422` com `reason: SPOOFING_DETECTED` ou `POOR_QUALITY`

**Sub-cenário D2 — Face Mismatch (somente se referência existir no S3):**
1. Garantir que `cpf_teste_D` tem referência de rosto A no S3
2. `POST /api/v1/liveness/sessions` com `cpf_teste_D`
3. `POST /validate` com imagem de rosto B (pessoa diferente)
- [ ] Response `422` com `reason: FACE_MISMATCH`

**Sub-cenário D3 — Sessão expirada:**
1. `POST /api/v1/liveness/sessions` → obter sessionId
2. Aguardar 6 minutos (TTL 5 min + margem)
3. `POST /validate` com o sessionId expirado
- [ ] Response `422` com `reason: SESSION_NOT_FOUND`

## Boas Práticas para os Testes de Integração

- Usar CPFs de teste distintos por cenário para evitar interferência entre cenários
- Limpar estado do Redis antes/depois de cada teste (`@BeforeEach` / `@AfterEach`)
- Não limpar S3 automaticamente — apenas documentar o estado esperado como pré-condição
- Usar `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` se Cenário B depende de A

## Acceptance Criteria

- [ ] Cenário A: fluxo de cadastro completo funciona com AWS real
- [ ] Cenário B: `compareFaces` executado com S3 pointer, nova versão criada no bucket
- [ ] Cenário C: Fast Track retorna em ≤ 100ms com `ACTIVE_BY_CACHE`
- [ ] Cenário D1: imagem sem rosto rejeitada com 422
- [ ] Cenário D2: rosto diferente rejeitado com `FACE_MISMATCH`
- [ ] Cenário D3: sessão expirada retorna `SESSION_NOT_FOUND`
- [ ] Nenhum teste de integração passa quando credenciais AWS estão ausentes (falha explícita, não silenciosa)

## O que NÃO deve ser feito
- Não usar LocalStack ou mocks de AWS nesta história
- Não usar CPFs reais de pessoas reais
- Não commitar imagens de teste no repositório — referenciar por path local ou variável de ambiente
- Não depender de estado de testes anteriores sem documentar a pré-condição explicitamente
