# Verificação da primeira entrega

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
