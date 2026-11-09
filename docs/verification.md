# Verificação da versão 0.2

Data: 9 de novembro de 2026, horário de Brasília. Ambiente Linux na nuvem, JDK 21.

| Fronteira | Evidência |
| --- | --- |
| Suíte | `./mvnw -B verify`: 72 testes, 0 falhas; 1 ignorado de propósito (avaliação paga) |
| Avaliação versionada | 47/47 casos com o provedor simulado |
| Browser → Agent → demonstração | Chromium headless (Playwright), 1365×900 e iPhone 13: saldo, resumo, continuação “E no mês anterior?”, esclarecimento, recusa de escrita, vencidas + hoje, 504 simulado com **Tentar novamente**, *Nova conversa* apagando o contexto; sem overflow horizontal; o único erro de console foi o 504 injetado |
| Configuração | `node --check app.js`; `docker compose config --quiet` |
| Imagem Docker | **Não validada localmente**: o ambiente não tinha daemon Docker. O CI do PR executa o build |
| Repositório de referência | Apenas lido (commit `9c6c099`); nada alterado |

Não houve chamada paga a modelo nem acesso a Fintry ou dados reais. A homologação real continua pendente.

---

## Verificação da primeira entrega (0.1)

Data: 6 de novembro de 2026, horário de Brasília.

História validada: usuário escolhe uma pergunta no chat, a UI envia JSON com CSRF, o agente seleciona e executa a ferramenta autorizada e apresenta valores, período, fonte e limitações.

| Fronteira | Evidência |
| --- | --- |
| Java 21 e Maven Wrapper | `mvnw.cmd -B verify`: 48 testes, sem falhas ou testes ignorados |
| Spring AI → provedor | Cliente real configurado contra servidor HTTP local do protocolo Chat Completions, verificando saída tipada, 600 tokens e ausência de credenciais Fintry no payload |
| Agent → Fintry | Servidor HTTP de contrato: rotas existentes, cookie individual, empresas concorrentes, revogação e falhas |
| Browser → Agent → demonstração → UI | Navegador Chromium headless em perfil isolado: quatro perguntas, valores esperados, períodos e abertura das fontes |
| Desktop e celular | 1365×900 e 390×844; sem overflow horizontal no celular, consulta de pendências legível e nenhum erro JavaScript |
| Prompt malicioso na interface | Pedido de troca de empresa e instrução para ignorar regras: nenhuma ferramenta executada |
| Configuração | `node --check src/main/resources/static/app.js` e `docker compose config --quiet` |
| Repositório de referência | `git status --short` vazio; código, banco e interface preservados |

O controle nativo do navegador Windows foi encerrado porque a automação não conseguiu identificar a URL atual com confiança. A validação de interface foi feita depois por um teste headless do projeto em outro perfil, sem operar a janela pessoal do usuário.

Os testes Windows de HTTP usaram `jdk.net.unixdomain.tmpdir=C:/Temp/fintry-java` para contornar uma falha do JDK na criação de sockets sob o aplicativo desktop. Não foi alterada nenhuma configuração global do sistema.

Não houve chamada paga a um modelo nem acesso a dados financeiros reais. A integração com conta real, sessão do produto e proxy HTTPS ainda precisa de homologação conforme [o contrato](fintry-integration.md).
