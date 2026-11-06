"use strict";
const form = document.querySelector("#chat-form");
const input = document.querySelector("#message");
const send = document.querySelector("#send");
const conversation = document.querySelector("#conversation");
const status = document.querySelector("#status");
let csrf;
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
  document.querySelectorAll("[data-question]").forEach((button) => {
    button.disabled = !ready || busy;
  });
}
async function fetchJson(url, options) {
  const response = await fetch(url, {
    ...options,
    credentials: "same-origin",
    signal: AbortSignal.timeout(35000),
  });
  const data = await response.json();
  if (!response.ok)
    throw new Error(data.message || "Consulta indisponível. Tente novamente.");
  return data;
}
async function initialize() {
  try {
    csrf = await fetchJson("api/csrf");
    const context = await fetchJson("api/context");
    document.querySelector("#mode").textContent =
      `${context.integration === "demo" ? "Demonstração · Dados fictícios" : "Fintry Finance · Dados reais"} · ${context.provider === "simulated" ? "Provedor simulado (sem IA generativa)" : "Modelo por API"} · Empresa: ${context.companyId}`;
    ready = true;
  } catch (error) {
    document.querySelector("#mode").textContent =
      error.message +
      " Na integração real, acesse pelo proxy autenticado do Fintry.";
  }
  update();
}
input.addEventListener("input", update);
document.querySelectorAll("[data-question]").forEach((button) =>
  button.addEventListener("click", () => {
    input.value = button.dataset.question;
    update();
    form.requestSubmit();
  }),
);
form.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!ready || busy || !input.value.trim()) return;
  const message = input.value.trim();
  busy = true;
  update();
  const turn = element("article", undefined, "turn");
  turn.append(element("p", message, "user"));
  conversation.append(turn);
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
    const reply = element("div", undefined, "answer");
    reply.append(element("p", data.message));
    for (const evidence of data.evidence) {
      reply.append(
        element("p", evidence.period, "period"),
        element("p", evidence.explanation),
        element("p", evidence.limitation, "limitation"),
      );
      const details = element("details");
      details.append(
        element(
          "summary",
          `Fonte · ${evidence.tool} · ${evidence.status === "ok" ? "consultada" : "indisponível"}`,
        ),
        element("code", evidence.source),
      );
      reply.append(details);
    }
    turn.append(reply);
  } catch (error) {
    turn.append(
      element(
        "p",
        error.name === "TimeoutError"
          ? "A consulta demorou mais que o esperado. Tente novamente."
          : error.message,
        "error",
      ),
    );
  } finally {
    busy = false;
    status.textContent = "";
    update();
    input.focus();
    turn.scrollIntoView({ block: "nearest" });
  }
});
initialize();
