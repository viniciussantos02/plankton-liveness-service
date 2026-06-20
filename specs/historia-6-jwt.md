# História 6 — Emissão do Token de Validação Final (JWT)

## Objetivo
Assinar e emitir um JWT com as informações de aprovação do usuário, substituindo o token placeholder da História 4.

## Dependências
- História 4 concluída (orquestração de validação funcionando)
- História 5 concluída (Fast Track e estados de token definidos)

## Arquivos a Criar/Modificar

| Arquivo | Pacote | Ação |
|---------|--------|------|
| `JwtTokenProvider.java` | `security` | Novo: geração e assinatura de JWT |
| `TokenState.java` | `enums` | Novo: enum de estado do token |
| `LivenessValidationService.java` | `service` | Substituir placeholder pelo JWT real |
| `pom.xml` | — | Adicionar dependência JJWT |

## Dependência Maven a Adicionar no `pom.xml`

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.x</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.x</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.x</version>
    <scope>runtime</scope>
</dependency>
```

## Especificação Técnica

### `TokenState` (enum)

```java
public enum TokenState {
    ACTIVE_VERIFIED,   // Aprovado via Rekognition (IA)
    ACTIVE_BY_CACHE    // Aprovado via Device Trust (Fast Track)
}
```

### `JwtTokenProvider.java`

```java
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.ttl-seconds}")
    private long ttlSeconds;

    public String generate(String cpf, String deviceId, String origin, TokenState state) {
        // Assinar com HMAC-SHA256
        // Claims obrigatórios:
        //   issuer: "plankton-liveness"
        //   subject: cpf
        //   deviceId: deviceId
        //   origin: origin
        //   state: state.name()
        //   issuedAt: agora
        //   expiration: agora + ttlSeconds
    }
}
```

### Claims do JWT

| Claim | Tipo | Valor | Origem |
|-------|------|-------|--------|
| `iss` (issuer) | String | `"plankton-liveness"` | Fixo |
| `sub` (subject) | String | CPF do usuário | Parâmetro |
| `deviceId` | String | ID do dispositivo | Parâmetro |
| `origin` | String | Sistema de origem (ex: `bank123`) | Sessão Redis |
| `state` | String | `ACTIVE_VERIFIED` ou `ACTIVE_BY_CACHE` | Parâmetro |
| `iat` (issuedAt) | Long | Timestamp de emissão | Gerado automaticamente |
| `exp` (expiration) | Long | `iat + ttlSeconds` | Configurado em `jwt.ttl-seconds` |

### Assinatura
- Algoritmo: `HS256` (HMAC-SHA256)
- Chave: `${JWT_SECRET}` via `application.yml` → `${jwt.secret}`
- Nunca hardcodar a chave

### Integração com `LivenessValidationService`

Substituir o token placeholder nos dois pontos de retorno:

**Fast Track (História 5):**
```java
return new ValidateResponse(
    SessionStatus.VALIDATED,
    jwtTokenProvider.generate(cpf, deviceId, origin, TokenState.ACTIVE_BY_CACHE)
);
```

**Cenário A e B (aprovação via IA):**
```java
return new ValidateResponse(
    SessionStatus.VALIDATED,
    jwtTokenProvider.generate(cpf, deviceId, origin, TokenState.ACTIVE_VERIFIED)
);
```

## Acceptance Criteria

- [ ] JWT gerado é válido e verificável com a chave configurada em `${JWT_SECRET}`
- [ ] Claim `iss` = `"plankton-liveness"` em todos os tokens emitidos
- [ ] Claim `sub` = CPF do usuário
- [ ] Claims `deviceId`, `origin`, `state` presentes no payload
- [ ] Tokens de Fast Track têm `state: "ACTIVE_BY_CACHE"`
- [ ] Tokens de aprovação via IA têm `state: "ACTIVE_VERIFIED"`
- [ ] TTL do token configurado via `jwt.ttl-seconds` (não hardcoded)
- [ ] Chave de assinatura vem de `${JWT_SECRET}` (não hardcoded)
- [ ] `JwtTokenProvider` está no pacote `security`
- [ ] `TokenState` está no pacote `enums`

## O que NÃO deve ser feito
- Não hardcodar o secret JWT em nenhum arquivo versionado
- Não usar algoritmo assimétrico (RS256) — usar HMAC-SHA256 (HS256)
- Não criar endpoint de validação de token — este serviço apenas emite
- Não expor o secret no log ou em respostas de erro
