# Especificação Técnica: plankton-liveness-service (Backend)

Este documento descreve a arquitetura de backend, contratos de API, requisitos e backlog do microsserviço em Java responsável pela orquestração do fluxo de biometria facial e antifraude (*Facial Liveness* e *Face Match*).

---

## 1. Engenharia de Requisitos

### Requisitos Funcionais (RF)
* **RF01 - Inicialização de Sessão:** O sistema deve permitir a criação de uma sessão de liveness temporária atrelada a um CPF e a um ID de dispositivo.
* **RF02 - Validação de Sessão Ativa:** O sistema deve garantir que o upload da biometria ocorra apenas em sessões criadas previamente e que estejam dentro do tempo de vida útil (TTL).
* **RF03 - Estratégia de Device Trust (Fast Track):** O sistema deve verificar no cache se o par `DeviceID + CPF` foi validado com sucesso nos últimos 30 dias. Em caso positivo, deve aprovar a transação imediatamente, pulando as chamadas de IA.
* **RF04 - Orquestração de Biometria (Cadastro vs. Verificação):**
  * **Cenário A (Cadastro):** Se o CPF não possuir imagem de referência no S3, o sistema deve validar o *Liveness* (Prova de Vida) na IA. Se aprovado, salva a foto como referência.
  * **Cenário B (Re-validação):** Se o CPF já possuir imagem no S3, o sistema deve validar o *Liveness* e realizar o *Face Match* (Comparação Facial) entre a foto atual e a de referência.
* **RF05 - Versionamento de Biometria:** O sistema deve salvar novas fotos de re-validação bem-sucedidas sobrescrevendo o arquivo de referência no S3, utilizando o versionamento nativo do bucket para manter o histórico.
* **RF06 - Emissão de Assinatura de Confiança (JWT):** Após qualquer fluxo de aprovação (via cache ou via IA), o sistema deve emitir um JWT assinado atestando o sucesso da validação.
* **RF07 - Ofuscação de PII via Hash Determinístico:** O sistema deve aplicar uma função de hash SHA-256 com Secret (Salt) sobre o CPF do cliente antes de gerar o caminho (`key`) de diretórios no S3, garantindo a privacidade dos dados sensíveis.

### Requisitos Não Funcionais (RNF)
* **RNF01 - Latência de Processamento:** O tempo total de processamento nos cenários que envolvem IA não deve ultrapassar 2 segundos. Cenários de *Fast Track* (Redis) devem responder em menos de 100ms.
* **RNF02 - Armazenamento Seguro e Volatilidade:** Os dados de sessão de curta duração devem residir em memória (Redis) com TTL estrito de 5 minutos.
* **RNF03 - Modelo de Payload de Imagem:** A imagem deve ser transmitida via JSON encapsulada como uma String em formato `Base64`.
* **RNF04 - Isolamento de Infraestrutura Local:** Toda a stack de persistência (S3/MinIO e Redis) e motores de IA devem ser passíveis de emulação local (Docker/LocalStack) para desenvolvimento.

---

## 2. Desenho Arquitetural do Fluxo

O fluxo abaixo exemplifica o comportamento lógico que o microsserviço Java executa ao receber uma tentativa de validação:

    [POST /validate recebido]
              │
              ▼
    1. O sessionId existe no Redis?
              ├── NÃO ──► [HTTP 404] Sessão expirada ou inválida!
              │
              └── SIM (Recupera o CPF e o Device ID guardados na sessão)
                      │
                      ▼
             2. Consulta Redis: Device ID confiável para este CPF (TTL 30 dias)?
                      ├── SIM ──► [APROVA DIRETO] (Gera JWT, pula S3 e IA)
                      │
                      └── NÃO
                              │
                              ▼
                     3. Consulta S3: Existe "profiles/{hash_do_cpf}/reference.png"?
                              ├── NÃO (Primeira vez) ──► [CENÁRIO A: CADASTRO]
                              │                           - Valida Liveness na IA
                              │                           - Salva no S3 (Cria reference)
                              │                           - Registra Trust no Redis (30 dias)
                              │
                              └── SIM (Retorno) ─────► [CENÁRIO B: RE-VALIDAÇÃO]
                                                          - Baixa imagem de referência do S3
                                                          - Valida Liveness + CompareFaces na IA
                                                          - Sobrescreve no S3 (Auto-versionado)
                                                          - Registra Trust no Redis (30 dias)

---

## 3. Contratos de API (API Specs)

### Endpoint 1: Inicializar Sessão de Liveness
Prepara o ambiente e gera o identificador temporário que o SDK mobile utilizará.

* **Path:** `/api/v1/liveness/sessions`
* **HTTP Method:** `POST`
* **Headers:**
    ```http
    Content-Type: application/json
    X-Device-ID: bbb111_origem
    ```
* **Request Body:**
    ```json
    {
      "cpf": "22222222222",
      "origin": "bank123"
    }
    ```

#### Respostas Possíveis (Responses)
* **Response (HTTP 201 Created):**
    ```json
    {
      "sessionId": "6a7b8c9d-e1f2-3a4b-5c6d-7e8f9a0b1c2d",
      "status": "INITIALIZED",
      "ttlInSeconds": 300
    }
    ```
* **Cenário Erro - Sessão Inválida ou Expirada (HTTP 400 Bad Request):**
    ```json
    {
      "status": "REJECTED",
      "reason": "INVALID_REQUEST",
      "message": "Problemas ao iniciar a sessão de liveness. Verifique os dados enviados e tente novamente."
    }
    ```

---

### Endpoint 2: Enviar e Validar Biometria Facial
Recebe a foto tirada pelo dispositivo e decide o fluxo de validação (Cache, Cadastro ou Comparação).

* **Path:** `/api/v1/liveness/sessions/{sessionId}/validate`
* **HTTP Method:** `POST`
* **Path Variables:**
  * `sessionId`: UUID gerado no Endpoint 1 (ex: `6a7b8c9d-e1f2-3a4b-5c6d-7e8f9a0b1c2d`).
* **Headers:**
    ```http
    Content-Type: application/json
    Authorization: Bearer <JWT_Embarcado_SDK>
    ```
* **Request Body:**
    ```json
    {
      "imageFormat": "image/png",
      "base64Image": "iVBORw0KGgoAAAANSUhEUgAA..." 
    }
    ```

#### Respostas Possíveis (Responses)

* **Cenário Sucesso - Aprovado (HTTP 200 OK):**
    ```json
    {
      "status": "VALIDATED",
      "verificationToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3N1ZXIiOiJwbGFua3Rvbi1saXZlbmVzcyIsImN..."
    }
    ```

* **Cenário Erro - Sessão Inválida ou Expirada (HTTP 422 Unprocessable Entity):**
    ```json
    {
      "status": "REJECTED",
      "reason": "SESSION_NOT_FOUND",
      "message": "A sessão de liveness informada expirou ou não existe."
    }
    ```

* **Cenário Erro - Divergência Biométrica / Suspeita de Fraude (HTTP 422 Unprocessable Entity):**
    ```json
    {
      "status": "REJECTED",
      "reason": "SPOOFING_DETECTED",
      "message": "A análise de biometria facial indicou uma possível tentativa de fraude."
    }
    ```

* **Cenário Erro - Baixa qualidade de imagem enviada para validação (HTTP 422 Unprocessable Entity):**
    ```json
    {
      "status": "REJECTED",
      "reason": "POOR_QUALITY",
      "message": "A análise de biometria facial indicou que a qualidade da imagem é insuficiente para validação, tente novamente."
    }
    ```

---

## 4. Plano de Ação & Backlog (Histórias e Tasks)

### [EPIC] Core Backend - Plankton Liveness

#### História 1: Setup do Ecossistema e Infraestrutura Local
> **Como** desenvolvedor do projeto,  
> **Quero** configurar o projeto base em Java Spring Boot e as dependências de infraestrutura via Docker e Terraform,  
> **Para** habilitar o desenvolvimento local isolado.
* **Subtasks:**
  * [ ] Inicializar projeto Spring Boot (Java 21 LTS) via Spring Initializr.
  * [ ] Adicionar dependências no `pom.xml`: Spring Web, Spring Data Redis, Lombok, e AWS SDK v2 (S3 e Rekognition) e Jakarta (Validação).
  * [ ] Criar arquivo `docker-compose.yml` com os serviços do **Redis** e **LocalStack** (emulando apenas o S3, o LocalStack ainda não tem suporte para Rekognition).
  * [ ] Configurar também dentro do `docker-compose.yml` a parte de visualização (interface visual) do **Redis** e **LocalStack**.
  * [ ] Criar e executar Terraform para provisionar o bucket `plankton-liveness-biometrics` dentro do **LocalStack**, habilitando o recurso de versionamento e criptografia de objetos.
  * [ ] Criar e executar Terraform para provisionar a parte do Rekognition dentro da AWS, pois o **LocalStack** ainda não oferece suporte para este serviço.

#### História 2: Criação de Sessão e Persistência Temporária
> **Como** microsserviço de Liveness,  
> **Quero** expor um endpoint para criar intenções de biometria e salvá-las no Redis,  
> **Para** garantir a integridade do fluxo e evitar acessos diretos ao validador.
* **Subtasks:**
  * [ ] Criar a classe de configuração `@Configuration` com método `@Bean` para a configuração do `RedisTemplate` com serializadores de String e JSON.
  * [ ] Criar o modelo/objeto `LivenessSession` (id, cpf, deviceId, status).
  * [ ] Criar Enum `SessionStatus` (INITIALIZED, VALIDATED, REJECTED) para ser usado no response das chamadas de criação de sessão e validação de biometria.
  * [ ] Criar Enum `RejectionReason` (SPOOFING_DETECTED, FACE_MISMATCH, POOR_QUALITY, SESSION_NOT_FOUND, INVALID_REQUEST) para detalhar os motivos de rejeição.
  * [ ] Implementar o request controller `LivenessSessionController` e o endpoint `POST /api/v1/liveness/sessions`.
  * [ ] Implementar o service `LivenessSessionService` focado em salvar o registro no Redis aplicando a expiração estrita de 5 minutos (TTL).
  * [ ] Criar `SessionInvalidRequestException` personalizada para lidar com erros de criação de sessão e retornar mensagens claras.
  * [ ] Criar global exception handler (`@ControllerAdvice`) `LivenessSessionExceptionHandler` para capturar e formatar as respostas de erro de forma consistente seguindo os exemplos definidos.

#### História 3: Camada de Abstração de Armazenamento e Ofuscação de PII
> **Como** componente de segurança e persistência de dados,  
> **Quero** criar uma estrutura que interaja com o S3 aplicando hash unidirecional e determinístico nos identificadores (CPF),  
> **Para** isolar o SDK bruto da AWS e garantir a privacidade dos dados dos usuários no storage de objetos sem necessidade de reversão (decriptação).
* **Subtasks:**
  * [ ] Criar a classe de configuração (`@Configuration`) `AwsConfig` com método `@Bean` para a configuração do `S3Client` apontando para o endpoint local do LocalStack.
  * [ ] Criar um componente utilitário `CryptoUtils` contendo uma função de Hash SHA-256 determinística (CPF + Secret via application.yml).
  * [ ] Implementar lógicas onde, para qualquer consulta ao S3 (Check, Upload ou Download), o sistema **recalcula o hash** do CPF recebido na requisição para montar o caminho (`key`) do objeto, evitando assim que o CPF do cliente seja exposto.
  * [ ] Criar a classe `S3StorageAdapter` para fazer a comunicação com o S3.
  * [ ] Implementar o método `boolean exists(String obfuscatedKey)` para buscar a foto de referência usando o hash calculado.
  * [ ] Implementar o método `String upload(String obfuscatedKey, byte[] content)` que realiza o `.putObject()` de forma versionada.
  * [ ] Implementar o método `byte[] download(String obfuscatedKey)` para recuperar os bytes da imagem de referência usando o hash calculado do cpf para buscar no nome da pasta `bucket/{cpfHash}/reference.png`.

#### História 4: Integração com Provedor de IA e Regras de Negócio
> **Como** núcleo de inteligência do microsserviço,  
> **Quero** processar a imagem em Base64, validar o Liveness e realizar o Face Match acionando a AWS,  
> **Para** decidir se o usuário é legítimo ou um fraudador.
* **Subtasks:**
  * [ ] Criar o método `@Bean` de configuração para o `RekognitionClient` dentro da classe `AwsConfig`.
  * [ ] Criar a classe utilitária `AwsRekognitionAdapter`.
  * [ ] Implementar os métodos `Object validateLiveness(byte[] imageBytes)` e `Object compareFaces(byte[] sourceImageBytes, byte[] targetImageBytes)` utilizando as APIs `DetectFaces` e `CompareFaces` do Rekognition, respectivamente.
  * [ ] Desenvolver método `boolean validateLiveness(byte[] imageBytes)` para a integração com o método de detecção facial / liveness da AWS e validação usando o objeto de response do SDK.
  * [ ] Desenvolver método `boolean compareFaces(byte[] sourceImageBytes, byte[] targetImageBytes)` para a integração com o método de comparação facial (`CompareFaces`), passando a imagem atual enviada pelo usuário e a imagem de referência recuperada do S3.
  * [ ] Implementar o fluxo de tomada de decisão estruturado (Cenário A vs Cenário B) dentro da camada de Service.
  * [ ] Criar `SessionNotFoundException`, `SpoofingDetectedException`, `FaceMismatchException` e `PoorQualityImageException` personalizadas para lidar com os erros específicos de validação e retornar mensagens claras de acordo com as regras de negócio.
  * [ ] Criar global exception handler (`@ControllerAdvice`) `LivenessValidationExceptionHandler` para capturar e formatar as respostas de erro de forma consistente seguindo os exemplos definidos.

#### História 5: Motor de Device Trust (Mecanismo de Fast Track)
> **Como** validador de segurança,  
> **Quero** consultar e registrar os dispositivos validados no Redis por 30 dias,  
> **Para** otimizar a experiência do usuário e economizar custos com APIs de IA.
* **Subtasks:**
  * [ ] Adicionar lógica de verificação no início do service (`LivenessValidationService`) de validação buscando a chave padrão `device:trust:{deviceId}:{cpf}`.
  * [ ] Desenvolver o método de gravação de sucesso, salvando a chave de confiança no Redis com TTL de 30 dias, ao final das validações.

#### História 6: Emissão do Token de Validação Final (JWT)
> **Como** microsserviço validador,  
> **Quero** assinar e emitir um token JWT com as informações de aprovação do usuário,  
> **Para** externar o resultado da validação com garantia de não-violação.
* **Subtasks:**
  * [ ] Adicionar as dependências da biblioteca `io.jsonwebtoken (JJWT)` no `pom.xml`.
  * [ ] Criar a classe `JwtTokenProvider`.
  * [ ] Criar Enum de estado do token (`TokenState`) com os valores `ACTIVE_VERIFIED`, `ACTIVE_BY_CACHE`.
  * [ ] Configurar os claims especificados no payload do JWT (`issuer: plankton-liveness`, `subject: cpf`, `deviceId`, `origin`, `ttl` e `estado`).
  * [ ] Realizar a assinatura digital do token utilizando uma chave simétrica segura configurada no ambiente.

#### História 7: Cobertura de Testes Unitários (Qualidade de Código)
> **Como** engenheiro de software do projeto,  
> **Quero** criar testes unitários isolados utilizando JUnit 5 e Mockito,  
> **Para** garantir a estabilidade das funções internas e regras de negócio sem depender de serviços externos ativos.
* **Subtasks:**
  * [ ] Criar testes unitários para o componente `CryptoUtils`, validando se a função de hash SHA-256 é estritamente determinística e reage corretamente a variações de Secret/Salt.
  * [ ] Criar testes unitários para o `JwtTokenProvider`, garantindo a correta inserção e leitura de claims customizados e validando o comportamento com tokens expirados.
  * [ ] Criar testes unitários para a classe de negócio principal `LivenessValidationService` utilizando `@Mock` para simular as respostas de sucesso e falha controladas do `S3StorageAdapter` e do `AwsRekognitionAdapter`.
  * [ ] Garantir cobertura completa dos fluxos de exceção (`SpoofingDetectedException`, `PoorQualityImageException`, etc.), verificando se os métodos disparam os erros corretos diante de respostas simuladas e negativas da IA.

#### História 8: Testes de Fluxo de Integração Ponta a Ponta (End-to-End Sessions)
> **Como** validador de qualidade e segurança do sistema,  
> **Quero** realizar testes de integração ponta a ponta utilizando o ambiente, local com Docker,
> **Para** certificar que o ecossistema (Redis, S3 e IA) interaja perfeitamente em cada um dos cenários críticos de negócio e antifraude.
* **Subtasks:**
  * [ ] Testar o **Cenário de Integração Feliz A (Primeiro Acesso/Cadastro):** Simular a criação de sessão via Endpoint 1, o envio da primeira selfie no Endpoint 2, garantindo que o sistema consulte o S3 (retorne inexistente), acione o `detectLiveness` da IA com sucesso, salve a imagem no bucket usando o CPF mascarado, registre o *Device Trust* e emita o JWT com estado `ACTIVE_VERIFIED`.
  * [ ] Testar o **Cenário de Integração Feliz B (Re-validação com Match):** Simular a jornada de um usuário recorrente que já possui foto de referência no S3. Garantir que o sistema baixe a imagem master, execute o `compareFaces` com sucesso, salve a nova versão da foto de forma auto-versionada e emita o JWT.
  * [ ] Testar o **Cenário de Integração Feliz C (Fast Track via Cache):** Simular o fluxo de curtíssima latência onde o par `DeviceID + CPF` já consta no Redis por ter sido aprovado recentemente. Validar se o sistema intercepta a chamada de início, pula a camada de IA/S3 e emite o JWT com estado `ACTIVE_BY_CACHE` em menos de 100ms.
  * [ ] Testar o **Cenário de Exceção e Bloqueio D (Tentativa de Spoofing):** Forçar o envio de um payload simulando uma imagem estática/inválida para garantir que o fluxo de decisão dispare a `SpoofingDetectedException`, limpe o contexto e devolva o HTTP 422 com o motivo `SPOOFING_DETECTED`.
  * [ ] Testar o **Cenário de Exceção e Bloqueio E (Divergência Facial/Invasão):** Simular uma tentativa de re-validação onde o usuário atual difere do dono original da conta (S3). Validar o disparo da `FaceMismatchException` e o retorno HTTP 422.