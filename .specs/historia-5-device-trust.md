# História 5 — Motor de Device Trust (Fast Track)

## Objetivo
Implementar o mecanismo de Fast Track: consultar e registrar dispositivos confiáveis no Redis com TTL de 30 dias, evitando chamadas desnecessárias à IA.

## Dependências
- História 4 concluída (`LivenessValidationService` com orquestração básica funcionando)

## Arquivos a Modificar

| Arquivo | Pacote | Ação |
|---------|--------|------|
| `LivenessValidationService.java` | `service` | Adicionar verificação e registro de device trust |

Nenhum arquivo novo precisa ser criado nesta história.

## Especificação Técnica

### Posição no Fluxo de Validação

O Device Trust deve ser verificado **após** confirmar que a sessão existe e **antes** de chamar S3 ou Rekognition:

```
1. Buscar sessão no Redis → NOT FOUND: 422
2. Extrair cpf, deviceId
3. ← INSERIR AQUI: verificar device trust
4. Verificar S3 → Cenário A ou B
5. Rekognition
6. Upload S3
7. ← INSERIR AQUI: registrar device trust
8. Emitir JWT
```

### Verificação de Device Trust (Fast Track)

**Chave Redis:** `device:trust:{deviceId}:{cpf}`

```java
String trustKey = "device:trust:" + deviceId + ":" + cpf;
boolean isTrusted = redisTemplate.hasKey(trustKey);

if (isTrusted) {
    // Emitir JWT com TokenState.ACTIVE_BY_CACHE
    // Retornar imediatamente sem chamar S3 ou Rekognition
    return new ValidateResponse(SessionStatus.VALIDATED, jwtTokenProvider.generate(cpf, deviceId, origin, TokenState.ACTIVE_BY_CACHE));
}
```

### Registro de Device Trust (após aprovação pela IA)

Deve ser chamado ao final de qualquer fluxo aprovado via Rekognition (Cenário A e Cenário B):

```java
String trustKey = "device:trust:" + deviceId + ":" + cpf;
redisTemplate.opsForValue().set(trustKey, "trusted", Duration.ofDays(30));
```

**TTL:** 30 dias (2592000 segundos)

### Tokens JWT por Origem da Aprovação

| Origem | TokenState |
|--------|------------|
| Device Trust (Fast Track) | `ACTIVE_BY_CACHE` |
| Validação via Rekognition (IA) | `ACTIVE_VERIFIED` |

O `TokenState` é injetado no JWT na História 6. Nesta história, garantir que o caminho correto está sendo chamado com o estado correto.

## Acceptance Criteria

- [ ] Se `device:trust:{deviceId}:{cpf}` existe no Redis → resposta imediata com `ACTIVE_BY_CACHE`, sem chamar S3 nem Rekognition
- [ ] Após aprovação via IA (Cenário A ou B) → registrar `device:trust:{deviceId}:{cpf}` no Redis com TTL de 30 dias
- [ ] Fast Track nunca aciona `S3StorageAdapter` nem `AwsRekognitionAdapter`
- [ ] Chave de trust usa o CPF literal (não o hash) — a ofuscação do CPF é somente para o path do S3
- [ ] TTL do device trust é exatamente 30 dias
- [ ] Aprovação via IA ainda registra trust mesmo que o trust já existisse (renova TTL)

## Regras de Negócio

- O CPF na chave Redis de device trust é em **plain text** — diferente do S3 onde é hasheado
- O device trust é por par `deviceId + cpf` — um device com CPFs diferentes tem chaves separadas
- O Fast Track não verifica biometria — confia no histórico de 30 dias

## O que NÃO deve ser feito
- Não hashar o CPF na chave Redis de device trust (o hash é só para o path do S3)
- Não criar nova classe/componente — toda lógica fica em `LivenessValidationService`
- Não alterar a chave de sessão Redis — a chave de trust é separada e diferente
