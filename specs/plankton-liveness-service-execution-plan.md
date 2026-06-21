# Especificação Técnica: plankton-liveness-service (Backend)

Este documento descreve a arquitetura de backend, contratos de API, requisitos e backlog do microsserviço em Java responsável pela orquestração do fluxo de biometria facial e antifraude (*Facial Liveness* e *Face Match*) utilizando infraestrutura real AWS.

---

## 1. Engenharia de Requisitos

### Requisitos Funcionais (RF)
* **RF01 - Inicialização de Sessão:** O sistema deve permitir a criação de uma sessão de liveness temporária atrelada a um CPF e a um ID de dispositivo.
* **RF02 - Validação de Sessão Ativa:** O sistema deve garantir que o upload da biometria ocorra apenas em sessões criadas previamente e que estejam dentro do tempo de vida útil (TTL).
* **RF03 - Estratégia de Device Trust (Fast Track):** O sistema deve verificar no cache se o par `DeviceID + CPF` foi validado com sucesso nos últimos 30 dias. Em caso positivo, deve aprovar a transação imediatamente, pulando as chamadas de IA.
* **RF04 - Orquestração de Biometria (Cadastro vs. Verificação):**
  * **Cenário A (Cadastro):** Se o CPF não possuir imagem de referência no S3, o sistema deve validar o *Liveness* (Prova de Vida) na IA através dos bytes enviados. Se aprovado, salva a foto no S3 como referência.
  * **Cenário B (Re-validação):** Se o CPF já possuir imagem no S3, o sistema deve validar o *Liveness* com a imagem atual e acionar o *Face Match* (Comparação Facial) instruindo o Rekognition a ler a imagem de referência diretamente do bucket S3, sem download prévio pelo microsserviço.
* **RF05 - Versionamento de Biometria:** O sistema deve salvar novas fotos de re-validação bem-sucedidas sobrescrevendo o arquivo de referência no S3, utilizando o versionamento nativo do bucket para manter o histórico.
* **RF06 - Emissão de Assinatura de Confiança (JWT):** Após qualquer fluxo de aprovação (via cache ou via IA), o sistema deve emitir um JWT assinado atestando o sucesso da validação.
* **RF07 - Ofuscação de PII via Hash Determinístico:** O sistema deve aplicar uma função de hash SHA-256 com Secret (Salt) sobre o CPF do cliente antes de gerar o caminho (`key`) de diretórios no S3, garantindo a privacidade dos dados sensíveis.

### Requisitos Não Funcionais (RNF)
* **RNF01 - Latência de Processamento:** O tempo total de processamento nos cenários que envolvem IA não deve ultrapassar 2 segundos. Cenários de *Fast Track* (Redis) devem responder em menos de 100ms.
* **RNF02 - Armazenamento Seguro e Volatilidade:** Os dados de sessão de curta duração devem residir em memória (Redis) com TTL estrito de 5 minutos.
* **RNF03 - Modelo de Payload de Imagem:** A imagem deve ser transmitida via requisição HTTP binária utilizando o formato nativo **`multipart/form-data`**, eliminando sobrecargas de processamento e rede causadas por strings textuais.
* **RNF04 - Ambiente e Provedores de Nuvem:** Toda a stack de persistência de objetos (S3) e o motor de IA (Rekognition) devem rodar diretamente em contas oficiais da AWS. O cache distribuído deve utilizar uma instância Redis (Dockerizada localmente para desenvolvimento).

---

## 2. Desenho Arquitetural do Fluxo

O fluxo abaixo exemplifica o comportamento lógico e otimizado que o microsserviço Java executa ao integrar nativamente o S3 ao AWS Rekognition:

    [POST /validate recebido (multipart/form-data)]
                          │
                          ▼
    1. O sessionId existe no Redis?
              ├── NÃO ──► [HTTP 422] Sessão expirada ou inválida!
              │
              └── SIM (Recupera o CPF e o Device ID guardados na sessão)
                      │
                      ▼
             2. Consulta Redis: Device ID confiável para este CPF (TTL 30 dias)?
                      ├── SIM ──► [APROVA DIRETO] (Gera JWT, pula S3 e IA)
                      │
                      └── NÃO (Extrai byte[] do arquivo multipart enviado)
                              │
                              ▼
                     3. Consulta S3: Existe "profiles/{hash_do_cpf}/reference.png"?
                              ├── NÃO (Primeira vez) ──► [CENÁRIO A: CADASTRO]
                              │                           - Valida Liveness na IA (via Bytes)
                              │                           - Salva no S3 (Cria reference)
                              │                           - Registra Trust no Redis (30 dias)
                              │
                              └── SIM (Retorno) ─────► [CENÁRIO B: RE-VALIDAÇÃO]
                                                          - Valida Liveness da foto atual (via Bytes)
                                                          - Executa CompareFaces (IA lê a foto master
                                                            diretamente do S3 via S3Object pointer)
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
      "message": "Sessão não encontrada ou expirada. Inicie uma nova sessão e tente novamente"
    }
    ```

---

### Endpoint 2: Enviar e Validar Biometria Facial
Recebe o arquivo binário da foto tirada pelo dispositivo e decide o fluxo de validação (Cache, Cadastro ou Comparação).

* **Path:** `/api/v1/liveness/sessions/{sessionId}/validate`
* **HTTP Method:** `POST`
* **Path Variables:**
  * `sessionId`: UUID gerado no Endpoint 1 (ex: `6a7b8c9d-e1f2-3a4b-5c6d-7e8f9a0b1c2d`).
* **Headers:**
    ```http
    Content-Type: multipart/form-data
    Authorization: Bearer <JWT_Embarcado_SDK>
    ```
* **Multipart Body Params:**
  * `file`: Arquivo de imagem binário (formatos aceitos: `.png` ou `.jpeg`).

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

#### História 1: Setup do Ecossistema e Infraestrutura Real AWS
> **Como** desenvolvedor do projeto,  
> **Quero** configurar o projeto base em Java Spring Boot e a infraestrutura diretamente na nuvem AWS via Terraform, mantendo apenas o cache local,  
> **Para** habilitar o desenvolvimento conectado ao ambiente real.
* **Subtasks:**
  * [ ] Inicializar projeto Spring Boot (Java 21 LTS) via Spring Initializr.
  * [ ] Adicionar dependências no `pom.xml`: Spring Web, Spring Data Redis, Lombok, AWS SDK v2 (S3 e Rekognition) e Jakarta (Validação).
  * [ ] Criar arquivo `docker-compose.yml` contendo apenas o serviço do **Redis** e sua interface visual para controle do cache de sessões e trusts localmente.
  * [ ] Criar arquivos do Terraform (`main.tf`, `variables.tf`) configurando o provedor AWS real.
  * [ ] Provisionar o bucket S3 real dentro do arquivo Terraform `plankton-liveness-biometrics` com suporte nativo a versionamento, criptografia `true`.
  * [ ] Configurar as políticas de IAM necessárias para que as credenciais locais tenham permissão total de leitura/escrita no S3 e execução no Rekognition.

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

#### História 3: Camada de Abstração de Persistência Otimizada no S3 Real
> **Como** componente de segurança e persistência de dados,  
> **Quero** criar uma estrutura que interaja com o S3 real aplicando hash determinístico no CPF e eliminando tráfego desnecessário de download,  
> **Para** expor apenas as operações de checagem de existência e upload de novas referências.
* **Subtasks:**
  * [ ] Criar a classe de configuração (`@Configuration`) `AwsConfig` com método `@Bean` para o `S3Client` apontando para a região oficial da AWS, utilizando a cadeia de credenciais padrão (`DefaultCredentialsProvider`).
  * [ ] Criar um componente utilitário `CryptoUtils` contendo uma função de Hash SHA-256 determinística (CPF + Secret via application.yml).
  * [ ] Criar a classe `S3StorageAdapter` para fazer a comunicação com a AWS.
  * [ ] Implementar o método `boolean exists(String obfuscatedKey)` que executa um `.headObject()` rápido para verificar se a foto de referência já existe no bucket real.
  * [ ] Implementar o método `String upload(String obfuscatedKey, byte[] content)` que realiza o `.putObject()` de forma versionada e protegida.

#### História 4: Integração de Alta Performance com AWS Rekognition (S3 Pointer)
> **Como** núcleo de inteligência do microsserviço,  
> **Quero** validar o Liveness com os bytes da selfie atual e realizar a comparação facial apontando o Rekognition diretamente para a imagem de referência do S3,  
> **Para** reduzir custos de tráfego de rede, latência e consumo de CPU no microsserviço Java.
* **Subtasks:**
  * [ ] Criar o método `@Bean` de configuração para o `RekognitionClient` dentro da classe `AwsConfig` conectado à região oficial AWS.
  * [ ] Criar a classe utilitária `AwsRekognitionAdapter`.
  * [ ] Configurar o endpoint `POST /api/v1/liveness/sessions/{sessionId}/validate` para consumir `MediaType.MULTIPART_FORM_DATA_VALUE` e mapear o parâmetro `@RequestParam("file") MultipartFile file`.
  * [ ] Desenvolver método `boolean validateLiveness(byte[] imageBytes)` integrado ao método `DetectFaces` da AWS passando os bytes da selfie capturada no app mobile.
  * [ ] Desenvolver método `boolean compareFaces(String sourceReferenceKey, byte[] targetCurrentBytes)` integrado à API `CompareFaces` configurando a imagem de origem como um ponteiro direto do S3 (`Image.builder().s3Object(...)`) e a imagem de destino como bytes em memória (`Image.builder().bytes(...)`).
  * [ ] Implementar a orquestração do fluxo de tomada de decisão (Cenário A vs Cenário B) na camada de Service, usando o adapter do S3 para checar existência e o adapter do Rekognition para processar as imagens.
  * [ ] Criar as exceções de negócio customizadas (`SpoofingDetectedException`, `FaceMismatchException`, etc.) e o `@ControllerAdvice` `LivenessValidationExceptionHandler` para o mapeamento dos erros 422.

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

#### História 8: Testes de Fluxo de Integração Ponta a Ponta (Ambiente Real AWS)
> **Como** validador de qualidade e segurança do sistema,  
> **Quero** realizar testes de integração ponta a ponta conectados à infraestrutura em nuvem da AWS,  
> **Para** certificar que as credenciais e políticas de IAM, o ciclo de vida do S3 e os motores de IA ajam perfeitamente de forma integrada.
* **Subtasks:**
  * [ ] Testar o **Cenário de Integração Feliz A (Primeiro Acesso/Cadastro Nuvem):** Executar a criação de sessão via Endpoint 1, enviar uma imagem válida via Multipart no Endpoint 2. Validar que o sistema detecta a não existência prévia no S3 real, aciona o `detectFaces` na AWS, faz o upload do arquivo e retorna o JWT `ACTIVE_VERIFIED`.
  * [ ] Testar o **Cenário de Integração Feliz B (Re-validação Direta no S3 via Rekognition):** Simular um fluxo recorrente onde a chave do S3 já existe. Garantir que a chamada de `compareFaces` do Rekognition processe o arquivo de referência direto da nuvem usando apenas a string do path/key e valide com sucesso a imagem atual enviada no Multipart, gerando a nova versão da foto por cima no bucket.
  * [ ] Testar o **Cenário de Integração Feliz C (Fast Track via Cache Local):** Simular o bypass de infraestrutura onde a chave `device:trust` está ativa no Redis local. Garantir que a requisição retorne o token `ACTIVE_BY_CACHE` imediatamente, sem chamar o S3 ou o Rekognition real na AWS.
  * [ ] Testar o **Cenário de Exceção D (Tentativa de Spoofing / Divergência Facial):** Validar as respostas reais do motor AWS Rekognition ao simular imagens inválidas ou rostos diferentes do cadastrado, inspecionando o correto recebimento dos códigos HTTP 422 correspondentes.
