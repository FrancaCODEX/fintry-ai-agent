"use strict";
const form = document.querySelector("#chat-form");
const input = document.querySelector("#message");
const send = document.querySelector("#send");
const reset = document.querySelector("#reset");
const conversation = document.querySelector("#conversation");
const status = document.querySelector("#status");
const RETRYABLE_STATUS = new Set([429, 502, 503, 504]);
const STATUS_LABEL = {
  ok: "Consultado",
  empty: "Sem registros",
  unavailable: "Indisponível",
  info: "Explicação",
};
const OUTCOME_NOTICE = {
  partial: "warning",
  failed: "warning",
  invalid_plan: "warning",
  clarification: "info",
  refused: "neutral",
  unsupported: "neutral",
  no_data: "info",
};
let csrf;
let context;
let ready = false;
let busy = false;

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function update() {
  document.querySelector("#counter").textContent =
    `${input.value.length} / 2000`;
  send.disabled = !ready || busy || !input.value.trim();
  reset.disabled = !ready || busy;
  document.querySelectorAll("[data-question]").forEach((button) => {
    button.disabled = !ready || busy;
  });
}

/** Errors carry the HTTP status so the UI can decide whether a retry makes sense. */
async function fetchJson(url, options) {
  let response;
  try {
    response = await fetch(url, {
      ...options,
      credentials: "same-origin",
      signal: AbortSignal.timeout(35000),
    });
  } catch (cause) {
    const error = new Error(
      cause.name === "TimeoutError"
        ? "A consulta demorou mais que o esperado. Nenhum valor foi estimado."
        : "Sem conexão com o assistente. Verifique a rede.",
    );
    error.retryable = true;
    throw error;
  }
  let data = {};
  try {
    data = await response.json();
  } catch {
    data = {};
  }
  if (!response.ok) {
    const error = new Error(
      data.message || "Consulta indisponível. Tente novamente.",
    );
    error.status = response.status;
    error.retryable = RETRYABLE_STATUS.has(response.status);
    throw error;
  }
  return data;
}

function modeChips(data) {
  const mode = document.querySelector("#mode");
  mode.replaceChildren();
  const demo = data.integration === "demo";
  const simulated = data.provider === "simulated";
  mode.append(
    element(
      "span",
      demo ? "Demonstração · dados fictícios" : "Fintry Finance · dados reais",
      demo ? "chip demo" : "chip",
    ),
    element(
      "span",
      simulated ? "Provedor simulado · sem IA generativa" : "Modelo por API",
      simulated ? "chip demo" : "chip",
    ),
    element("span", `Empresa da sessão: ${data.companyId}`, "chip"),
  );
}

async function initialize() {
  try {
    csrf = await fetchJson("api/csrf");
    context = await fetchJson("api/context");
    modeChips(context);
    ready = true;
  } catch (error) {
    document.querySelector("#mode").textContent =
      error.message +
      " Na integração real, acesse pelo proxy autenticado do Fintry.";
  }
  update();
}

function questionButton(question) {
  const button = element("button", question, "chip-button");
  button.type = "button";
  button.dataset.question = question;
  button.addEventListener("click", () => ask(question));
  return button;
}

function evidenceCard(evidence) {
  const card = element("article", undefined, `evidence status-${evidence.status}`);
  const head = element("div", undefined, "evidence-head");
  head.append(
    element("h4", evidence.title),
    element("span", STATUS_LABEL[evidence.status] || evidence.status, "state"),
  );
  card.append(
    head,
    element("p", evidence.period, "period"),
    element("p", evidence.explanation, "explanation"),
    element("p", evidence.limitation, "limitation"),
  );
  const details = element("details");
  const list = element("dl");
  list.append(
    element("dt", "Fonte"),
    element("dd", evidence.source),
    element("dt", "Ferramenta"),
    element("dd", evidence.tool),
  );
  details.append(element("summary", "Detalhes técnicos"), list);
  card.append(details);
  return card;
}

function retryButton(message) {
  const button = element("button", "Tentar novamente", "retry");
  button.type = "button";
  button.addEventListener("click", () => {
    button.disabled = true;
    ask(message);
  });
  return button;
}

function renderAnswer(data, message) {
  const reply = element("div", undefined, "answer");
  reply.dataset.outcome = data.outcome;
  const notice = OUTCOME_NOTICE[data.outcome];
  reply.append(element("p", data.message, notice ? `notice ${notice}` : "lead"));
  if (data.contextUsed) reply.classList.add("follow-up");
  for (const evidence of data.evidence) reply.append(evidenceCard(evidence));
  if (data.suggestions?.length) {
    const chips = element("div", undefined, "chips");
    chips.setAttribute("aria-label", "Perguntas sugeridas");
    data.suggestions.forEach((q) => chips.append(questionButton(q)));
    reply.append(chips);
  }
  if (data.retryable) reply.append(retryButton(message));
  if (data.integration === "demo")
    reply.append(
      element(
        "p",
        `Demonstração com dados fictícios${data.provider === "simulated" ? " · provedor simulado" : ""}`,
        "meta",
      ),
    );
  return reply;
}

function renderError(error, message) {
  const box = element("div", undefined, "answer");
  const session = error.status === 401 || error.status === 403;
  box.append(
    element(
      "p",
      session
        ? `${error.message} Entre no Fintry novamente e recarregue esta página.`
        : error.message,
      "notice error",
    ),
  );
  if (!session && error.retryable) box.append(retryButton(message));
  return box;
}

async function ask(message) {
  if (!ready || busy || !message.trim()) return;
  busy = true;
  update();
  const turn = element("article", undefined, "turn");
  turn.append(element("p", message, "user"));
  const loading = element("div", undefined, "answer loading");
  loading.setAttribute("aria-busy", "true");
  loading.append(element("span", "Consultando as fontes financeiras", "dots"));
  turn.append(loading);
  conversation.append(turn);
  turn.scrollIntoView({ block: "nearest" });
  input.value = "";
  status.textContent = "Consultando as fontes financeiras…";
  try {
    const data = await fetchJson("api/chat", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        [csrf.headerName]: csrf.token,
      },
      body: JSON.stringify({ message }),
    });
    loading.replaceWith(renderAnswer(data, message));
    status.textContent = "Resposta recebida.";
  } catch (error) {
    loading.replaceWith(renderError(error, message));
    status.textContent = "A consulta não foi concluída.";
  } finally {
    busy = false;
    update();
    input.focus();
    turn.scrollIntoView({ block: "nearest" });
  }
}

input.addEventListener("input", update);
input.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    form.requestSubmit();
  }
});
document.querySelectorAll(".suggestions [data-question]").forEach((button) =>
  button.addEventListener("click", () => ask(button.dataset.question)),
);
form.addEventListener("submit", (event) => {
  event.preventDefault();
  ask(input.value.trim());
});
reset.addEventListener("click", async () => {
  if (busy) return;
  try {
    await fetchJson("api/conversation/reset", {
      method: "POST",
      headers: { [csrf.headerName]: csrf.token },
    });
    conversation.querySelectorAll(".turn").forEach((turn) => turn.remove());
    status.textContent = "Nova conversa iniciada. O contexto anterior foi apagado.";
  } catch (error) {
    status.textContent = error.message;
  }
  input.focus();
});
initialize();
