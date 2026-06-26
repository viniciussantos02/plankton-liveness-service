# Plankton Liveness Service vs. Soluções Empresariais de Liveness (Referência: Unico)

Este documento compara o que foi construído neste projeto de estudo com o que empresas como a **Unico** (anteriormente Acesso Digital) fazem em produção para validação biométrica. O objetivo é entender as lacunas conceituais e técnicas entre um backend de orquestração artesanal e um produto de identidade digital de classe enterprise.

---

## 1. O que é este projeto (Plankton Liveness Service)

O `plankton-liveness-service` é um microsserviço Java/Spring Boot de estudo que orquestra um fluxo de **Facial Liveness** e **Face Match** usando infraestrutura AWS (S3 + Rekognition). Ele **não opera sozinho** — é consumido por um **SDK mobile próprio** (iOS/Android) que fica responsável por toda a camada de captura: enquadramento do rosto, verificação de iluminação, qualidade da imagem e pré-processamento antes de enviar ao backend.

A divisão de responsabilidades é clara:

| Camada | Responsável | O que faz |
|---|---|---|
| **SDK Mobile** | App cliente (proprietário) | Captura, enquadramento, processamento, envio via multipart |
| **Backend (este serviço)** | `plankton-liveness-service` | Sessão, orquestração, IA, armazenamento, JWT |

O backend resolve o seguinte:

- Criar uma sessão temporária atrelada a um CPF e dispositivo (TTL 5 min no Redis)
- Receber a selfie já processada pelo SDK via `multipart/form-data`
- Decidir entre três caminhos: **Fast Track** (cache de dispositivo confiável), **Cadastro** (primeira foto) ou **Re-validação** (compara com referência no S3)
- Emitir um JWT como atestado de aprovação

É uma arquitetura de backend limpa, com boas práticas de segurança (hash de CPF, S3 pointer, TTL estrito), que deliberadamente usa serviços gerenciados da AWS como motor de inteligência e tem um SDK próprio controlando a camada de captura.

---

## 2. O que é a Unico e o que ela faz

A **Unico** é a maior empresa brasileira de identidade digital. Seu produto principal — o **Unico Check** — é uma plataforma completa de verificação de identidade usada por bancos, fintechs, seguradoras, operadoras de telecom e varejo para onboarding digital e autenticação contínua.

Não é apenas um serviço de liveness. É um **stack completo de antifraude biométrico** com SDK, motor de IA proprietário, base de dados biométrica nacional e integração com órgãos governamentais.

---

## 3. Comparação Técnica Detalhada

### 3.1 Captura da Imagem: SDK Proprietário vs. SDK Proprietário

Esta é a lacuna que **mais se fecha** com a existência do SDK mobile próprio do Plankton. Ambos os sistemas têm um SDK que controla a captura antes de enviar ao backend.

| Aspecto | Plankton (estudo) | Unico (produção) |
|---|---|---|
| SDK de captura | Sim — proprietário do projeto | Sim — Unico Check SDK |
| Quem controla a captura | O SDK do app (controlado) | O SDK da Unico (controlado) |
| Pré-processamento no dispositivo | Sim: enquadramento, qualidade, iluminação | Sim: enquadramento, iluminação, nitidez, orientação |
| Canal de envio ao backend | HTTPS padrão via multipart | HTTPS + possível camada adicional de assinatura de payload |
| Assinatura do payload pelo SDK | Não — o backend não verifica se a imagem veio do SDK | Sim — o backend valida que a requisição foi originada pelo SDK legítimo |
| Proteção contra bypass da API | Parcial — um atacante técnico ainda pode chamar o backend direto | Alta — requisições fora do SDK são rejeitadas pelo backend |

**O que muda com o SDK próprio:** A principal vulnerabilidade da seção anterior ("um atacante pode enviar qualquer imagem via `curl`") fica **parcialmente mitigada** na prática legítima. Entretanto, do ponto de vista técnico, o backend do Plankton ainda aceita qualquer requisição `multipart` válida — ele não tem como verificar se a imagem veio do SDK ou de um cliente arbitrário. A Unico resolve isso com assinatura de payload no SDK que o backend valida, tornando o bypass inviável mesmo para um atacante que conhece o contrato da API.

**Lacuna que permanece:** liveness ativo. O SDK do Plankton faz pré-processamento de uma foto estática. O SDK da Unico pode exigir que o usuário execute ações em tempo real (piscar, sorrir, virar a cabeça) — o que torna inútil tentar usar uma foto ou vídeo gravado, mesmo que o atacante conheça a API.

---

### 3.2 Motor de Liveness: AWS Rekognition vs. IA Proprietária

| Aspecto | Plankton (estudo) | Unico (produção) |
|---|---|---|
| Motor de IA | AWS Rekognition (`DetectFaces`) | Modelos proprietários treinados com base brasileira |
| Tipo de liveness | **Passivo** — análise de foto estática | **Ativo** e **Passivo** disponíveis |
| Liveness ativo | Não implementado | Desafios: piscar, sorrir, virar a cabeça, seguir ponto na tela |
| Dataset de treino | Dataset global da Amazon | Dataset brasileiro com milhões de rostos nacionais |
| Anti-spoofing 3D | Não (2D only) | Sim: profundidade, sensores IR em dispositivos compatíveis |
| Detecção de máscara/foto/vídeo | Limitada ao que o Rekognition oferece | Detecção nativa de ataques de apresentação (PAD) |

**O que é Liveness Ativo:** Em vez de analisar uma foto, o sistema pede que o usuário execute uma ação em tempo real (piscar, sorrir). Isso torna impossível o ataque mais simples — mostrar uma foto impressa ou um vídeo de outra pessoa na câmera. O Plankton não implementa isso porque depende do AWS Rekognition, que não oferece esse recurso de forma nativa na API de análise de foto.

---

### 3.3 Face Match: Referência Local vs. Base Biométrica Nacional

| Aspecto | Plankton (estudo) | Unico (produção) |
|---|---|---|
| Referência para comparação | A própria foto salva na 1ª vez que o usuário usou o sistema | Base biométrica própria + CNH/RG do usuário + bases governamentais |
| Comparação com documento | Não | Sim: OCR + extração da foto do documento (CNH, RG, Passaporte) |
| Consulta governamental | Não | DENATRAN/SENATRAN (foto da CNH), Receita Federal (CPF), SERPRO |
| Histórico de rostos | 1 foto (sobrescrita com versionamento S3) | Histórico completo por usuário com versionamento biométrico |
| Resultado do face match | Binário: aprovado ou `FACE_MISMATCH` | Score de similaridade + pontuação de confiança + histórico de risco |

**Impacto crítico:** No Plankton, se alguém usa o sistema pela primeira vez com o rosto de outra pessoa, a foto fraudulenta vira a referência e todas as validações futuras serão aprovadas com aquele rosto. A Unico cruza com a foto da CNH do governo — uma fonte de verdade externa que o fraudador não controla.

---

### 3.4 Resultado da Validação: Binário vs. Score de Risco

| Aspecto | Plankton (estudo) | Unico (produção) |
|---|---|---|
| Resultado | Binário: `VALIDATED` ou `REJECTED` + reason | Score de confiança (0–100) + classificação de risco |
| Resposta ao cliente | JWT com `ACTIVE_VERIFIED` ou `ACTIVE_BY_CACHE` | Score + recomendação + sinais de fraude detalhados |
| Quem decide o threshold | Fixo no `application.yml` (90%) | Configurável por cliente/produto com política de risco própria |
| Revisão humana | Não existe | Fila de revisão manual para casos borderline |
| Feedback loop | Nenhum | Modelo retreinado com novos casos de fraude detectados |

---

### 3.5 Device Trust: Cache Simples vs. Inteligência de Dispositivo

| Aspecto | Plankton (estudo) | Unico (produção) |
|---|---|---|
| Identificador de dispositivo | `X-Device-ID` header (qualquer string) | Fingerprint criptográfico do hardware |
| O que é armazenado | `device:trust:{deviceId}:{cpf}` no Redis (30 dias) | Fingerprint + comportamento + histórico de transações |
| Sinais considerados | Apenas o par dispositivo+CPF | Device fingerprint, rede, comportamento de digitação, localização, hora |
| Risco de falsificação | Alto — um atacante pode enviar qualquer `X-Device-ID` | Muito difícil — fingerprint é derivado de hardware e SO |
| Fast Track inteligente | Baseado apenas em tempo (30 dias fixo) | Baseado em score de risco dinâmico (pode bloquear mesmo com cache ativo se houver anomalia) |

---

### 3.6 Arquitetura e Infraestrutura

| Aspecto | Plankton (estudo) | Unico (produção) |
|---|---|---|
| Infraestrutura de IA | AWS Rekognition (pay-per-use, compartilhado) | Clusters GPU dedicados com modelos proprietários |
| Armazenamento de biometria | S3 com uma foto por CPF | Banco de dados biométrico com replicação, histórico e indexação |
| Cache | Redis dockerizado localmente | Redis Enterprise com HA, replicação multi-região |
| SLA | Não definido (projeto de estudo) | 99,9%+ com contrato e penalidades |
| Latência garantida | RNF01: < 2s (sem garantia formal) | P99 monitorado, com alertas e escalonamento automático |
| Multi-região | Não | Sim, com failover automático |
| Auditoria | Nenhuma | Log imutável de cada transação com hash de integridade |

---

### 3.7 Segurança e Conformidade

| Aspecto | Plankton (estudo) | Unico (produção) |
|---|---|---|
| Proteção de PII | Hash SHA-256 do CPF no S3 (boa prática implementada) | LGPD compliance completo, DPO dedicado, controle de retenção |
| Certificações | Nenhuma | ISO 27001, SOC 2 Type II, PCI-DSS (onde aplicável) |
| Pentest | Não realizado | Pentest periódico por empresas especializadas |
| Gestão de chaves | Secrets em variáveis de ambiente | AWS KMS + rotação automática de chaves |
| Segregação de dados | Não | Isolamento por tenant/cliente com criptografia por namespace |
| Proteção contra replay attack | Não explícita | Nonce por sessão + validação de timestamp no SDK |

---

## 4. O que o Plankton faz bem (para um projeto de estudo)

É importante reconhecer as boas decisões de design presentes no Plankton:

- **SDK mobile próprio** — a camada de captura, enquadramento e pré-processamento é controlada pelo projeto, não deixada livre para o cliente
- **Separação clara de responsabilidades entre SDK e backend** — o SDK cuida da qualidade da imagem; o backend cuida da orquestração e da decisão
- **Hash determinístico do CPF** com secret/salt — protege PII no S3 sem impedir a busca
- **S3 pointer no Rekognition** — evita download da imagem de referência, reduz latência e custo de rede
- **TTL estrito de 5 minutos** na sessão — janela de ataque pequena
- **Device Trust com TTL de 30 dias** — modelo sensato de Fast Track que economiza chamadas de IA
- **Separação clara de responsabilidades no backend** — adapters, services, controllers bem delimitados
- **Versionamento do S3** para histórico das fotos de referência
- **JWT assinado** como atestado — o resultado da validação não pode ser forjado sem a chave

Esses padrões são corretos e usados também em sistemas de produção. O Plankton implementa a **espinha dorsal** de um sistema de liveness com SDK + backend, que é exatamente o modelo arquitetural que empresas como a Unico usam.

---

## 5. O que falta para ser um produto de produção

Em ordem de criticidade — considerando que o SDK mobile já existe:

1. **Assinatura de payload no SDK verificada pelo backend** — o backend deve validar que a requisição partiu do SDK legítimo, não de um cliente arbitrário
2. **Liveness ativo no SDK** — desafios interativos (piscar, sorrir) para prevenir ataques de apresentação mesmo por quem conhece a API
3. **Cruzamento com documento de identidade** — fonte de verdade externa (CNH/RG + DENATRAN) para o primeiro acesso
4. **Fingerprint criptográfico de hardware no `deviceId`** — o header atual pode ser qualquer string enviada pelo SDK; precisa ser derivado do hardware de forma não falsificável
5. **Score de risco em vez de binário** — permite políticas de risco configuráveis por produto
6. **Pipeline de anti-spoofing robusto** — detecção de deepfake, ataque de injeção de vídeo no nível do SDK e do backend
7. **Auditoria imutável** — log de cada transação com integridade verificável
8. **Revisão humana** para casos borderline
9. **Feedback loop de modelo** — aprender com fraudes detectadas
10. **Conformidade LGPD completa** — consentimento, retenção, exclusão de dados

---

## 6. Resumo Visual

```
Plankton Liveness Service
┌─────────────────────────────────────────────────┐
│  SDK Mobile (proprietário)                      │
│    ├── Enquadramento do rosto                   │
│    ├── Verificação de iluminação/qualidade      │
│    └── Pré-processamento da imagem              │
│         ↓ multipart/HTTPS                       │
│  Backend Spring Boot                            │
│    ├── Redis (sessão TTL 5min)                  │
│    ├── Redis (device trust TTL 30 dias)         │
│    ├── AWS Rekognition (detectFaces/compareFaces│
│    └── AWS S3 (1 foto de referência por CPF)    │
│         ↓                                       │
│  JWT → VALIDATED / REJECTED                     │
└─────────────────────────────────────────────────┘

Unico Check (simplificado)
┌─────────────────────────────────────────────────┐
│  SDK Nativo (captura criptografada + liveness   │
│  ativo + fingerprint de hardware)               │
│         ↓                                       │
│  API Gateway + WAF + Rate Limiting              │
│         ↓                                       │
│  Orquestrador de Fraude (score dinâmico)        │
│    ├── Motor de Liveness próprio (GPU)          │
│    ├── OCR + Extração de foto do documento      │
│    ├── Face Match (selfie vs. documento)        │
│    ├── Consulta DENATRAN/SERPRO/Receita Fed.    │
│    ├── Device Intelligence (comportamental)     │
│    └── Histórico biométrico do usuário          │
│         ↓                                       │
│  Score de Risco (0–100) + Classificação         │
│         ↓                                       │
│  Banco Biométrico + Auditoria Imutável          │
│         ↓                                       │
│  Resposta ao cliente (score + recomendação)     │
│  Webhook assíncrono (para casos demorados)      │
└─────────────────────────────────────────────────┘
```

---

## 7. Conclusão

O `plankton-liveness-service`, em conjunto com seu SDK mobile próprio, implementa o **mesmo modelo arquitetural** que empresas como a Unico usam: um SDK que controla a captura no dispositivo e um backend que orquestra a decisão de validação. Essa separação de responsabilidades é correta e está presente nos principais produtos de identidade digital do mercado.

A distância para um produto como o Unico não está na arquitetura geral — está na **profundidade de cada camada**. O SDK da Unico assina o payload que o backend valida; o motor de IA é proprietário e treinado com base brasileira; o face match cruza com documentos físicos e bases governamentais; o resultado é um score, não um binário. Cada uma dessas camadas adicionais reduz a superfície de ataque de forma independente.

Em termos práticos: o Plankton resolve bem o problema central (é aquele usuário, está vivo, está no dispositivo certo?) para o contexto de um projeto de estudo com infraestrutura real da AWS. O que falta são as camadas defensivas que tornam essa resposta confiável mesmo quando um atacante sofisticado tenta subverter o fluxo.
