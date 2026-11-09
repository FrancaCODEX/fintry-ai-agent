# Contrato verificado e operação real

Referência lida: `FrancaCODEX/fintry-finance`, commit `9c6c099c0df21553d42c95548d39557c1aa9bbf5` (0.39.1). Apenas leitura do checkout de referência. As instruções `AGENTS.md` dele foram lidas; nenhum arquivo desse repositório foi alterado.

## Autenticação existente

`SecurityConfig` usa `HttpSessionSecurityContextRepository`, CSRF e sessão HTTP. `AuthController` faz login por `POST /api/v1/auth/login` após obtenção de CSRF em `GET /api/v1/auth/csrf`. O Agent não executa login nem recebe senhas.

O browser envia o cookie `JSESSIONID` ao Agent no mesmo host do Fintry. O backend encaminha somente esse cookie a uma origem fixa configurada, sem redirecionamentos, e chama `GET /api/v1/auth/me`. Campos necessários:

```json
{
  "id": "UUID-do-usuario",
  "companyId": "UUID-da-empresa",
  "role": "VIEWER",
  "permissions": ["READ_FINANCE"]
}
```

`ADMIN`, `FINANCE` e `VIEWER` têm `READ_FINANCE` no Fintry atual. Nenhuma outra permissão é usada para ampliar o catálogo. O nome e e-mail presentes no DTO upstream não são necessários e são descartados. `CurrentUser` e `SessionAccessFilter` no Fintry validam usuário ativo, versão da autenticação e empresa associada ao usuário. A sessão nunca é substituída por uma conta técnica compartilhada.

Antes de cada ferramenta, o Agent chama `/auth/me` novamente e exige a mesma identidade/empresa com `READ_FINANCE`. O Fintry também autentica e autoriza a própria consulta financeira. Não há cache de dados ou de identidade entre usuários, nem cookie jar global no cliente HTTP. O contexto fica restrito à requisição atual.

## Rotas existentes consumidas

| Ferramenta | Requisição | Campos aproveitados |
| --- | --- | --- |
| Saldo/resumo mensal | `GET /api/v1/finance/summary?month=AAAA-MM` | `balance`, `income`, `expense`, `result` |
| Comparação mensal (0.2) | `GET /api/v1/finance/summary?month=AAAA-MM` | `income`, `expense`, `result`, `previousIncome`, `previousExpense`, `previousResult` |
| Contas a pagar | `GET /api/v1/finance/pending?group=OVERDUE\|TODAY\|NEXT7&type=EXPENSE&page=0&size=20` | `today`, `group` (deve ser igual ao pedido), `content[].description/dueDate/remainingAmount`, `totalElements`, `totals.expense` |
| Projeção | `GET /api/v1/reports/forecast?days=N&includeOverdue=false` | `start`, `end`, `openingBalance`, `closingBalance`, `pendingIncome`, `pendingExpense`, `minimumBalance`, `firstNegativeDate` |

Fontes no [código inspecionado](https://github.com/FrancaCODEX/fintry-finance/tree/9c6c099c0df21553d42c95548d39557c1aa9bbf5/backend/src/main/java/com/fintry): `auth/AuthController.java`, `auth/AuthService.java`, `auth/CurrentUser.java`, `auth/SessionAccessFilter.java`, `shared/security/SecurityConfig.java`, `finance/FinanceController.java`, `finance/FinanceService.java`, `finance/PendingController.java`, `finance/PendingService.java`, `report/ReportController.java`, `report/ReportService.java`.

`balance` é a soma dos saldos atuais, mesmo quando o resumo é de um mês anterior. Por isso a ferramenta de saldo é separada da explicação de realizados mensais. `income/expense/result` usam pagamentos efetivados no mês. Não reinterpretamos `flow` do resumo como previsão: ele é histórico.

Para um mês sem registros, `summary` devolve zeros com HTTP 200. O Agent mostra isso como **sem registros**, não como falha. Os campos `previous*` são opcionais na leitura: se faltarem, a comparação falha explicitamente e não usa zero. `OVERDUE` = vencimento anterior a hoje; `TODAY` = hoje. `NEXT7` começa amanhã e termina em D+7. `UPCOMING` no Fintry inclui hoje, mas não é usado nesta entrega. O Agent deixa esse recorte explícito. Os totais vêm da agregação completa do Fintry; a lista é limitada à primeira página e a interface sinaliza truncamento.

`forecast(days=N)` inclui hoje e D+N (N+1 pontos diários). Não inclui atrasados porque `includeOverdue=false`; essa limitação é sempre exibida. O Agent não recalcula previsões.

## Proxy no mesmo host

Como o projeto não compartilha o armazenamento interno de sessões do Fintry, a autenticação é validada pela API, não pela leitura do cookie em si. O seguinte é um exemplo de configuração **externa de infraestrutura**, a adaptar ao ambiente. Não foi aplicado a nenhum ambiente existente:

```nginx
server {
    listen 443 ssl;
    server_name fintry.example;
    # Configure ssl_certificate e ssl_certificate_key no seu ambiente.

    location /agent/ {
        proxy_pass http://agent:8092/;
        proxy_set_header Host $host;
        proxy_set_header Cookie $http_cookie;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 35s;
        client_max_body_size 8k;
    }
    location / {
        proxy_pass http://fintry:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
    }
}
```

Faça login normalmente no Fintry e abra `/agent/` no mesmo host. O cookie de sessão do Fintry deve ter `Path=/`, estar válido e ser enviado ao Agent. Configure `FINTRY_MODE=real`, `FINTRY_BASE_URL` para a origem interna confiável e `COOKIE_SECURE=true`. O cookie CSRF do Agent chama-se `FINTRY_AGENT_SESSION`, evitando colisão com `JSESSIONID`. Mensagens devem trazer o token obtido por `GET /agent/api/csrf`; o frontend faz isso automaticamente.

Não exponha a porta do Agent diretamente em uma implantação real; mantenha o acesso pelo proxy HTTPS. Confirme o acesso do usuário ao Fintry antes de abrir a interface. Sem sessão, a API retorna 401. Com sessão sem permissão, retorna 403. Não há login próprio do Agent nesta versão.

Não são necessárias mudanças de código, banco ou interface no Fintry para o contrato lido. Se o ambiente não permitir compartilhar o cookie pelo proxy, será necessário um contrato de delegação de identidade (por exemplo, credencial curta emitida e validada pelo Fintry com usuário, empresa, escopo de leitura, audiência e expiração). Isso **não existe no código inspecionado e não foi implementado ou presumido**. Não substitua essa ausência por um token inventado, cabeçalhos de identidade não verificados ou credenciais de administrador.

## Homologação pendente com dados reais

Os testes locais exercitam HTTP real contra um servidor de contrato controlado; não significam homologação contra uma instância do produto. Para homologar: usar ambiente de desenvolvimento com dois usuários de empresas diferentes; verificar `/api/context`; executar as quatro perguntas; comparar valores e períodos com o Fintry; revogar acesso e confirmar 401/403; testar sessão expirada; confirmar que nenhum endpoint de escrita é chamado. Não usar dados financeiros de produção para essa primeira validação.
