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
