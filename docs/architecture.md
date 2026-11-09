# Decisões e como explicá-las

## Uma aplicação, portas explícitas

Uma aplicação Spring Boot serve UI e backend, reduzindo infraestrutura e problemas de CORS. `FintryGateway` permite trocar fonte financeira sem alterar agente ou ferramentas; `AiProvider` permite trocar modelo sem acoplar autorização ao fornecedor. Não criamos banco, filas ou microsserviços porque a primeira versão consulta dados já existentes e cada pergunta é independente.

## Planejamento estruturado e execução Java

Usamos o modelo para a tarefa em que ele agrega valor: transformar linguagem natural em uma lista tipada de consultas. Spring AI converte a saída para `Plan`. O agente valida **todo** o plano antes de executar qualquer ferramenta. Não há loop ilimitado, execução de código ou ferramenta de URL/SQL genérico.

Esta versão usa **planejamento estruturado**, não o ciclo automático de tool calling do Spring AI. O contrato é deliberadamente pequeno: `BALANCE`, `SUMMARY`, `PENDING`, `FORECAST`, com argumentos fechados. A decisão facilita auditar número de chamadas, validar argumentos e explicar a fronteira de confiança. Um próximo incremento pode usar tool calling nativo mantendo as mesmas validações; isso não deve ampliar a superfície de autorização.

A explicação final é produzida em Java a partir dos DTOs retornados, com período, endpoint e limitações. Não há segundo pedido ao modelo para redigir a resposta. Isso reduz custo, exposição de dados e risco de alucinar valores. A contrapartida é uma linguagem menos flexível e sem interpretação financeira aberta. É uma primeira versão de assistente com seleção de ferramentas, não um planejador financeiro autônomo.

## Identidade fora do modelo

O cookie só vale depois de validado pelo Fintry. O backend constrói `UserContext` com usuário, empresa e permissões obtidos em `/auth/me`; nada disso é escolhido pelo prompt. Cada consulta usa o mesmo cookie individual. Não há cookie jar compartilhado. A revalidação reduz a janela de uso de sessão/permissões revogadas, e a autorização final continua no serviço financeiro.

Instruções maliciosas podem influenciar a seleção de **consultas permitidas**, pois um LLM não é um mecanismo de autorização. Mas não podem habilitar escrita ou acessar outra empresa pelo catálogo: não existem ferramentas de escrita, argumentos de empresa, banco ou credenciais expostos ao modelo. O sistema não depende de conseguir detectar todas as frases maliciosas. O roteador simulado possui recusas simples apenas para tornar suas limitações visíveis.

## Limites verificáveis

Mensagens possuem limite semântico e de transporte. Um executor com quatro trabalhadores e fila de capacidade zero rejeita saturação com 429. O prazo total cancela a tarefa, e as integrações HTTP têm prazos próprios. Cancelar localmente não garante cancelamento de processamento/cobrança no provedor remoto. Ferramentas são no máximo três e não fazem paginação ilimitada.

Falhas de sessão/permissão encerram a resposta, evitando continuar com identidade inválida. Falhas operacionais de ferramentas aparecem como indisponibilidade na evidência; valores não são preenchidos com zero. A projeção é uma hipótese calculada pelo serviço financeiro e sua limitação é exibida, mesmo quando todos os registros existem.

## Testes por fronteira

- Roteador: as perguntas escolhem o tipo correto e pedidos não suportados são recusados.
- Spring AI: a conversão de saída do modelo gera plano tipado; nomes de ferramenta desconhecidos falham.
- Agente/ferramentas: validação antes da execução, limites de chamadas e tempo, permissão e evidências.
- HTTP: servidor de contrato local comprova rotas GET, cookie individual, isolamento simultâneo, revogação, falhas e campos ausentes.
- Web: contexto, CSRF, tamanho, propriedades extras e indisponibilidade de rotas de escrita.

Não usamos API de IA paga nos testes. Não presumimos que testes de contrato substituem homologação com o Fintry real. O CI também valida o empacotamento Docker.

## Perguntas para entrevista

**Por que IA, em vez de palavras-chave?** O simulador deixa executar e testar sem custo. O modelo real interpreta variações de linguagem e devolve um contrato estruturado; os serviços financeiros e as regras de segurança são iguais nos dois casos.

**Quem calcula os valores?** O Fintry Finance, ou seu serviço demonstrativo. O Agent apenas solicita consultas e formata os resultados. Não reproduz cálculo financeiro no prompt.

**Como impedir uma instrução de “usar empresa B”?** A ferramenta não aceita empresa. O contexto é derivado da sessão validada e o próprio Fintry aplica o escopo. Testes tentam trocar empresa pelo corpo, cabeçalhos, prompt e contexto fabricado.

**Por que não colocar todo o extrato no prompt?** Não é necessário para escolher consultas e aumenta custo/exposição. Não enviamos resultados financeiros ao provedor nesta versão.

**O que você ainda não consegue afirmar?** Que a integração foi homologada com uma instância e contas reais, ou que determinado modelo tem boa precisão em toda linguagem financeira. Essas afirmações exigem validação externa e avaliações com perguntas representativas.

## Versão 0.2: robustez, consultas e continuidade

**Problemas encontrados na 0.1.** “Quanto saiu no mês passado?” era recusado. “Contas que vencem em 30 dias?” devolvia a mesma resposta genérica de um pedido de escrita. Um mês sem dados na demonstração aparecia como *indisponível*, misturando ausência de dados com falha. O prompt do modelo dizia para usar o mês atual quando faltava o período, e isso acontecia sem aviso.

**Decisão explícita do planejador.** O plano passou a ter `decision`: `QUERY`, `CLARIFY_PERIOD`, `CLARIFY_TOPIC`, `UNSUPPORTED`, `WRITE_REQUEST` ou `OTHER_COMPANY`. O Java associa cada decisão a uma mensagem fixa e **nunca mostra texto do modelo**. Um plano incoerente, como uma recusa acompanhada de consultas, decisão nula, nenhuma consulta ou mais de três consultas, vira `invalid_plan` e nada é executado. Trade-off: as respostas ficam menos fluidas, mas não há como uma injeção de prompt colocar texto na tela.

**Defaults resolvidos e anunciados em Java.** Se o modelo devolve `month=null` ou `days=null`, `FinancialTools.resolve` escolhe o mês atual ou 30 dias, e a resposta diz que esse padrão foi usado. Um período válido, mas fora do contrato, como 2020-01 ou 120 dias, gera um pedido de esclarecimento com o intervalo aceito. Um argumento malformado gera `invalid_plan`.

**Status das evidências:** `ok`, `empty`, `unavailable` e `info`. Ausência de registros não é falha, e falha não é zero.

**Novas consultas sem novas rotas.** A comparação usa os campos `previous*` que `/finance/summary` já devolve. O Agent mostra os dois meses e diz se o resultado foi maior ou menor, usando `compareTo`, mas **não calcula diferenças**. As contas vencidas e as que vencem hoje usam `group=OVERDUE|TODAY` no mesmo endpoint de pendências. O adaptador rejeita uma resposta cujo `group` seja diferente do pedido. `CONCEPTS` é um texto fixo, sem chamada ao Fintry, que explica saldo atual × resultado realizado × saldo projetado. As definições foram conferidas no `ReportService`: a projeção começa no saldo atual e só inclui atrasados com `includeOverdue=true`.

**Contexto de conversa.** Fica guardado na sessão HTTP do próprio Agent (`FINTRY_AGENT_SESSION`), que já existia para o CSRF. Guarda apenas a estrutura das consultas executadas (`kind` e período resolvido, até três) junto com `userId`, `companyId` e o horário. Antes de passar o contexto ao planejador, o agente confere se o usuário e a empresa são os mesmos e se o contexto tem menos de 10 minutos. O contexto é apagado em `POST /api/conversation/reset` (botão *Nova conversa*), em 401/403 e na expiração da sessão, depois de 15 minutos. Valores, texto da pergunta e cookie não são guardados, então **toda continuação consulta os valores novamente**. O modelo recebe só algo como `SUMMARY(month=2026-11, ...)`. O plano traz `followUp`, que apenas informa à interface que houve continuação e não concede nenhum acesso. Trade-off: só existe memória de um turno e ela não sobrevive a mais de uma instância sem sessão compartilhada. Isso basta para “E no mês anterior?” e evita um banco de conversas.

**Avaliação.** Veja [evaluation.md](evaluation.md). O conjunto avalia o resultado final do agente (planejador + validação + gateway), não o JSON bruto do plano. Assim, um esclarecimento conta como correto venha ele do modelo ou do Java.

## Roteiro

1. **Homologação real** (bloqueante): proxy + instância de desenvolvimento do Fintry + duas empresas; executar o roteiro de [fintry-integration.md](fintry-integration.md).
2. **Avaliação com modelo real**, autorizada pelo dono do projeto: rodar `RealModelPlanningEvaluationTest`, registrar a acurácia por categoria e ajustar o prompt.
3. **Métricas agregadas** (Micrometer/Actuator protegido) a partir da linha `fintry.agent.metrics`, com alertas para aumento de `failed` ou `invalid_plan`.
4. **Resposta redigida pelo modelo sobre as evidências**, opcional e com verificação de que todo número citado existe nas evidências.
5. **Períodos livres e categorias**, somente se o Fintry expuser um contrato GET adequado (`/reports?start&end` existe, mas é paginado por lançamento e exigiria definir totais confiáveis).

## Mais perguntas de entrevista

**Por que guardar a estrutura da consulta e não a resposta?** Um valor de dez minutos atrás pode já estar errado, e ainda ficaria guardado um dado sensível. Guardando só a estrutura, a continuação consulta o Fintry de novo, com a autorização atual.

**Como você sabe se o modelo melhorou ou piorou?** Pelo conjunto versionado de 47 casos com resultado e consultas esperados. As categorias de segurança precisam acertar 100%, mesmo que a acurácia geral oscile.
