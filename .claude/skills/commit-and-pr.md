# commit-and-pr

Skill exclusiva do senior-dev para commitar as alterações de uma história e abrir Pull Request no GitHub para aprovação do usuário.

## Pré-condições obrigatórias

Antes de executar, verifique:
1. O `spec-reviewer` aprovou a história (veredicto APROVADO ou APROVADO COM RESSALVAS)
2. Não há arquivos com secrets ou credenciais no staging area
3. O código compila sem erros (`mvn compile`)
4. Os testes unitários passam (`mvn test`)

Se qualquer pré-condição falhar, interrompa e reporte ao usuário.

## Passos de execução

### 1. Verificar estado do repositório
```bash
git status
git diff --staged
```

### 2. Compilar e testar
```bash
mvn compile
mvn test
```

### 3. Staged dos arquivos da história atual
Adicione apenas os arquivos relacionados à história implementada — nunca `git add .` cego.

```bash
git add <arquivos específicos da história>
```

Arquivos que NUNCA devem ser commitados:
- `.env`, `*.env`, `application-local.yml`
- Arquivos com credenciais AWS
- Arquivos de IDE (`.idea/`, `*.iml`)

### 4. Criar commit

Formato obrigatório da mensagem:
```
feat(historia-N): <descrição curta do que foi implementado>

- <item implementado 1>
- <item implementado 2>
- Spec: openapi.yaml endpoints afetados

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
```

### 5. Push e abertura do PR

```bash
git push origin <branch-atual>
gh pr create \
  --title "feat(historia-N): <título>" \
  --body "$(cat <<'EOF'
## História N — <nome da história>

## O que foi implementado
- item 1
- item 2

## Spec de referência
- `openapi.yaml`: endpoints afetados
- Requisitos: RF0X, RNF0X

## Revisão de spec
- Revisado pelo agente `spec-reviewer`
- Veredicto: APROVADO / APROVADO COM RESSALVAS

## Como testar
1. passo 1
2. passo 2

## Checklist
- [ ] Compila sem erros
- [ ] Testes unitários passando
- [ ] Spec reviewer aprovou
- [ ] Sem secrets commitados

🤖 Implementado por senior-dev agent | Revisado por spec-reviewer agent
EOF
)"
```

### 6. Reportar ao usuário

Ao final, exiba:
- URL do PR criado
- Branch do PR
- Lista de arquivos commitados
- Lembre o usuário de revisar e aprovar o PR no GitHub

## O que este skill NUNCA faz

- Nunca usa `--force` ou `--no-verify`
- Nunca commita na branch `main` diretamente
- Nunca pula os testes para agilizar
- Nunca abre PR sem aprovação prévia do spec-reviewer
