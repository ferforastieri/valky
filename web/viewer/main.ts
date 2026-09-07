import { API, labels, escape as e, date, clock, key } from "./lib/api";
import type {
  Camera,
  Event,
  Person,
  Place,
  Rule,
  Location,
  Update,
} from "./lib/api";
import { live } from "./lib/live";
const $ = <T extends HTMLElement = HTMLElement>(selector: string) =>
  document.querySelector<T>(selector)!;
const api = new API();
const paths: Record<string, string> = {
  camera: "M14 4h5v16h-5M4 7h10v10H4zM14 10l6-3v10l-6-3",
  bell: "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4",
  map: "M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0ZM9 10a3 3 0 1 0 6 0 3 3 0 1 0-6 0",
  rule: "M4 5h16M4 12h16M4 19h16M8 2v6M16 9v6M10 16v6",
  arrow: "m9 5 7 7-7 7",
  users:
    "M15 21v-3a5 5 0 0 0-10 0v3M10 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8M17 4a4 4 0 0 1 0 8M19 15a4 4 0 0 1 2 4v2",
  shield: "m12 2 8 4v6c0 5-8 10-8 10S4 17 4 12V6l8-4M8 12l3 3 5-6",
};
const icon = (name: string, cls = "") =>
  `<svg class="${cls}" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${paths[name] || paths.camera}"/></svg>`;
const empty = (title: string, text: string) =>
  `<div class="empty">${icon("shield")}<h3>${e(title)}</h3><p>${e(text)}</p></div>`;
const main = $("#main");
const dialog = $<HTMLDialogElement>("#detail");
let cameras: Camera[] = [];
let events: Event[] = [];
let people: Person[] = [];
let places: Place[] = [];
let rules: Rule[] = [];
let page = "overview";
let refreshing = false;
let alive = new AbortController();
let viewController = new AbortController();
let modalController = new AbortController();
let modalCleanups: (() => void)[] = [];
let viewCleanups: (() => void)[] = [];
let hasLoaded = false;
let signature = "";
let pageVersion = 0;
let dialogVersion = 0;
let search = "";
let eventCamera = "";
let eventType = "";
let eventState = "";
const messages: Record<string, [string, string]> = {
  overview: [
    "Sua casa, em um só lugar.",
    "Um olhar sobre as câmeras, os acontecimentos e quem importa.",
  ],
  cameras: [
    "Câmeras",
    "Acompanhe os ambientes e abra uma transmissão ao vivo.",
  ],
  events: ["Eventos", "Consulte os últimos 200 registros e suas mídias."],
  family: ["Família", "Últimas posições recebidas dos aparelhos da casa."],
  places: ["Áreas", "Locais acompanhados no mapa da família."],
  rules: ["Regras", "Critérios e ações configurados no aplicativo."],
  system: [
    "Sistema",
    "Informações desta instalação e preferências de consulta.",
  ],
};
const cameraName = (id?: string) =>
  cameras.find((c) => c.id === id)?.name || "Câmera";
const eventName = (event: Event) =>
  event.source === "tracking"
    ? `${String(event.metadata?.personName || "Pessoa")} ${event.type === "place_entered" ? "entrou em" : "saiu de"} ${String(event.metadata?.placeName || "uma área")}`
    : labels[event.type] || event.type;
const eventSource = (event: Event) =>
  event.source === "tracking"
    ? "Localização da família"
    : cameraName(event.cameraId);
const head = (name: string) =>
  `<div class="page-head"><div><span class="eyebrow">SUA CASA / ${e(name === "overview" ? "AGORA" : messages[name][0].toUpperCase())}</span><h1>${e(messages[name][0])}</h1><p>${e(messages[name][1])}</p></div><span class="badge">${new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "short" }).format(new Date())}</span></div>`;
const activity = (list: Event[]) =>
  list.length
    ? `<div class="panel">${list.map((ev) => `<button class="activity-row" data-event="${e(ev.id)}"><span class="tile-icon">${icon(ev.source === "tracking" ? "map" : "bell")}</span><span class="activity-main"><strong>${e(eventName(ev))}</strong><small>${e(eventSource(ev))}</small></span><time datetime="${e(ev.occurredAt)}">${clock(ev.occurredAt)}</time>${icon("arrow")}</button>`).join("")}</div>`
    : empty("Tudo tranquilo por aqui", "Nenhum evento registrado.");
const cameraGrid = (list: Camera[]) =>
  list.length
    ? `<div class="camera-grid">${list.map((c) => `<button class="camera-card" data-camera="${e(c.id)}"><div class="camera-preview">${icon("camera")}<img data-snapshot="${e(c.id)}" alt="Imagem de ${e(c.name)}" hidden/><span class="badge">${e(c.setupStatus === "ready" ? "● Disponível" : c.setupStatus === "failed" ? "Falha na câmera" : "Conectando")}</span></div><div class="camera-info"><span class="tile-icon">${icon("camera")}</span><div><h3>${e(c.name)}</h3><small>${e(c.host)}</small></div><span class="arrow">${icon("arrow")}</span></div></button>`).join("")}</div>`
    : empty(
        "Nenhuma câmera cadastrada",
        "Cadastre uma câmera pelo aplicativo para acompanhá-la aqui.",
      );
const avatar = (p: Person) =>
  `<span class="avatar">${p.avatarData?.startsWith("data:image/jpeg;base64,") ? `<img src="${e(p.avatarData)}" alt=""/>` : e(p.name.slice(0, 1).toUpperCase())}</span>`;
const members = (list: Person[]) =>
  list
    .map(
      (p) =>
        `<button class="member-card" data-person="${e(p.id)}">${avatar(p)}<span><strong>${e(p.name)}</strong><small>${date(p.lastLocatedAt)}</small></span><span class="arrow">${icon("arrow")}</span></button>`,
    )
    .join("");
function disposeView() {
  pageVersion++;
  viewController.abort();
  viewController = new AbortController();
  viewCleanups.splice(0).forEach((fn) => fn());
}
function render() {
  const active = document.activeElement;
  const focused =
    active instanceof HTMLInputElement && main.contains(active)
      ? {
          id: active.id,
          start: active.selectionStart,
          end: active.selectionEnd,
        }
      : null;
  disposeView();
  const version = pageVersion;
  let content = "";
  if (page === "overview") {
    const stats = [
      [
        "Câmeras disponíveis",
        cameras.filter((c) => c.setupStatus === "ready").length,
        `${cameras.length} cadastradas`,
        "camera",
      ],
      [
        "Eventos recentes",
        events.length,
        "Últimos registros recebidos",
        "bell",
      ],
      [
        "Família",
        people.length,
        `${people.filter((p) => p.lastLocatedAt).length} com localização`,
        "users",
      ],
      ["Regras", rules.length, "Configuradas no aplicativo", "rule"],
    ];
    const hours = Array.from(
      { length: 12 },
      (_, i) =>
        events.filter((ev) => {
          const ago = (Date.now() - Date.parse(ev.occurredAt)) / 3600000;
          return ago >= 11 - i && ago < 12 - i;
        }).length,
    );
    const max = Math.max(1, ...hours);
    content = `<div class="stats">${stats.map(([label, value, hint, ico]) => `<div class="stat"><div class="stat-label">${label}${icon(String(ico), "stat-icon")}</div><div class="stat-value">${value}</div><small>${hint}</small></div>`).join("")}</div><div class="section-title"><h2>Um olhar em casa</h2><a class="text-link" href="#cameras">Todas as câmeras →</a></div>${cameraGrid(cameras.slice(0, 3))}<div class="dashboard-bottom"><section><div class="section-title"><h2>Aconteceu por aqui</h2><a class="text-link" href="#events">Ver eventos →</a></div>${activity(events.slice(0, 5))}</section><section><div class="section-title"><h2>Nas últimas 12 horas</h2></div><div class="panel panel-pad"><span class="muted">Atividade registrada</span><div class="chart" role="img" aria-label="Eventos nas últimas 12 horas: ${hours.join(", ")}">${hours.map((n) => `<span style="height:${Math.max(4, (n / max) * 100)}%" title="${n} eventos"></span>`).join("")}</div><div class="chart-legend"><span>12 HORAS ATRÁS</span><span>AGORA</span></div></div><div class="section-title"><h2>Família</h2><a class="text-link" href="#family">Abrir mapa →</a></div>${people.length ? members(people.slice(0, 3)) : empty("Aguardando a família", "Os aparelhos vinculados aparecerão aqui.")}</section></div>`;
  } else if (page === "cameras") content = cameraGrid(cameras);
  else if (page === "events")
    content = `<div class="filter-bar"><input id="event-search" aria-label="Buscar eventos" placeholder="Buscar acontecimento…" value="${e(search)}"/><select id="event-camera" aria-label="Filtrar câmera"><option value="">Todas as câmeras</option>${cameras.map((c) => `<option value="${e(c.id)}" ${eventCamera === c.id ? "selected" : ""}>${e(c.name)}</option>`).join("")}<option value="tracking" ${eventCamera === "tracking" ? "selected" : ""}>Localização</option></select><select id="event-type" aria-label="Filtrar tipo"><option value="">Todos os tipos</option>${Object.entries(
      labels,
    )
      .map(
        ([id, label]) =>
          `<option value="${id}" ${eventType === id ? "selected" : ""}>${label}</option>`,
      )
      .join(
        "",
      )}</select><select id="event-state" aria-label="Filtrar status"><option value="">Todos os status</option><option value="pending" ${eventState === "pending" ? "selected" : ""}>Não reconhecidos</option><option value="ack" ${eventState === "ack" ? "selected" : ""}>Reconhecidos</option></select></div><div id="event-list"></div>`;
  else if (page === "family")
    content =
      people.length || places.length
        ? `<div class="family-layout"><div id="family-map" class="map" aria-label="Mapa da família"></div><div class="family-members">${members(people) || empty("Nenhum aparelho", "Vincule os aparelhos pelo aplicativo.")}</div></div>`
        : empty(
            "O mapa está aguardando a família",
            "Nenhum aparelho ou área foi cadastrado.",
          );
  else if (page === "places")
    content = places.length
      ? `<div class="family-layout"><div id="family-map" class="map" aria-label="Mapa de áreas"></div><div>${places.map((p) => `<article class="panel panel-pad" style="margin-bottom:12px"><span class="tile-icon">${icon("map")}</span><h3 style="margin-top:14px">${e(p.name)}</h3><p class="muted">Raio de ${e(p.radiusMeters)} metros</p><span class="pill">${p.enabled ? "Área ativa" : "Área desativada"}</span></article>`).join("")}</div></div>`
      : empty(
          "Nenhuma área cadastrada",
          "Configure os locais pelo aplicativo.",
        );
  else if (page === "rules")
    content = rules.length
      ? `<div class="rule-grid">${rules.map((r) => `<button class="rule-card" data-rule="${e(r.id)}"><span class="tile-icon">${icon("rule")}</span><h3>${e(r.name)}</h3><div class="rule-details"><span>${e(cameraName(r.cameraId))} · ${r.detectorTypes.map((d) => e(labels[d] || d)).join(", ")}</span><span>${e(schedule(r))}</span><span>${r.motion ? `Região selecionada · movimento por ${r.motion.minDurationSeconds} s` : "Detecção padrão da câmera"}</span></div><div class="rule-actions">${actionPills(r)}</div></button>`).join("")}</div>`
      : empty(
          "Nenhuma regra cadastrada",
          "As regras criadas no aplicativo aparecerão aqui.",
        );
  else if (page === "system")
    content = `<div id="system-content" class="loading"><span class="spinner"></span>Consultando instalação…</div>`;
  main.innerHTML = head(page) + content;
  if (page === "events") {
    renderEvents();
    $("#event-search").addEventListener("input", (ev) => {
      search = (ev.target as HTMLInputElement).value;
      renderEvents();
    });
    for (const id of ["camera", "type", "state"])
      $(`#event-${id}`).addEventListener("change", (ev) => {
        const value = (ev.target as HTMLSelectElement).value;
        if (id === "camera") eventCamera = value;
        if (id === "type") eventType = value;
        if (id === "state") eventState = value;
        renderEvents();
      });
  }
  if (focused) {
    const input = document.getElementById(
      focused.id,
    ) as HTMLInputElement | null;
    input?.focus({ preventScroll: true });
    input?.setSelectionRange(focused.start, focused.end);
  }
  void snapshots(main, viewController.signal, viewCleanups);
  if ($("#family-map"))
    void import("./lib/map")
      .then(({ familyMap }) => {
        if (version !== pageVersion) return;
        viewCleanups.push(
          familyMap($("#family-map"), page === "family" ? people : [], places),
        );
      })
      .catch(() => {
        if (version === pageVersion)
          $("#family-map").textContent =
            "Não foi possível abrir o mapa. As posições continuam disponíveis no histórico.";
      });
  if (page === "system") void renderSystem(version);
  document.querySelectorAll("[data-page]").forEach((link) => {
    if ((link as HTMLElement).dataset.page === page)
      link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  });
}
function renderEvents() {
  const list = events.filter(
    (ev) =>
      (!search ||
        `${eventName(ev)} ${eventSource(ev)}`
          .toLocaleLowerCase()
          .includes(search.toLocaleLowerCase())) &&
      (!eventCamera ||
        (eventCamera === "tracking"
          ? ev.source === "tracking"
          : ev.cameraId === eventCamera)) &&
      (!eventType || ev.type === eventType) &&
      (!eventState ||
        (eventState === "ack" ? !!ev.acknowledgedAt : !ev.acknowledgedAt)),
  );
  $("#event-list").innerHTML = list.length
    ? `<div class="panel table-wrap"><table class="event-table"><thead><tr><th>Acontecimento</th><th class="desktop-col">Origem</th><th>Quando</th><th class="desktop-col">Status</th><th><span class="muted">Detalhes</span></th></tr></thead><tbody>${list.map((ev) => `<tr><td><span class="event-type"><span class="tile-icon">${icon(ev.source === "tracking" ? "map" : "bell")}</span>${e(eventName(ev))}</span></td><td class="desktop-col">${e(eventSource(ev))}</td><td>${date(ev.occurredAt)}</td><td class="desktop-col"><span class="pill ${ev.acknowledgedAt ? "" : "pending"}">${ev.acknowledgedAt ? "Reconhecido" : "Não reconhecido"}</span></td><td><button class="icon-button" data-event="${e(ev.id)}" aria-label="Abrir ${e(eventName(ev))}">${icon("arrow")}</button></td></tr>`).join("")}</tbody></table></div>`
    : empty("Nenhum evento encontrado", "Experimente outros filtros.");
}
function schedule(r: Rule) {
  if (!r.schedule?.start) return "Todos os dias · 24 horas";
  const days = r.schedule.days
    .map((d) => ["Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb"][d])
    .join(", ");
  return `${days} · ${r.schedule.start}–${r.schedule.end} · ${r.schedule.timezone}`;
}
function actionPills(r: Rule) {
  return (
    [
      ["record", "Gravar"],
      ["notify", "Notificar"],
      ["alarm", "Alarme"],
    ]
      .filter(([k]) => r.actions[k as keyof typeof r.actions])
      .map(([, label]) => `<span class="pill">${label}</span>`)
      .join("") || '<span class="pill">Sem ação configurada</span>'
  );
}
async function snapshots(
  root: HTMLElement,
  signal: AbortSignal,
  cleanups: (() => void)[],
) {
  const images = Array.from(
    root.querySelectorAll<HTMLImageElement>("[data-snapshot]"),
  );
  let index = 0;
  const worker = async () => {
    while (index < images.length && !signal.aborted) {
      const img = images[index++];
      try {
        const blob = await api.blob(
          `/cameras/${key(img.dataset.snapshot!)}/snapshot`,
          signal,
        );
        if (signal.aborted) return;
        const url = URL.createObjectURL(blob);
        cleanups.push(() => URL.revokeObjectURL(url));
        img.src = url;
        img.hidden = false;
      } catch {
        if (!signal.aborted) {
          const notice = document.createElement("span");
          notice.className = "preview-failure";
          notice.textContent = "Imagem indisponível";
          img.parentElement?.append(notice);
        }
      }
    }
  };
  await Promise.all([worker(), worker()]);
}
async function renderSystem(version: number) {
  const results = await Promise.allSettled([
    api.get<Update>("/system/update", viewController.signal),
    api.get<{ maxAgeDays: number; maxStorageGB: number }>(
      "/settings/retention",
      viewController.signal,
    ),
    api.get<{ configured: boolean }>("/settings/push", viewController.signal),
  ]);
  if (version !== pageVersion) return;
  const update = results[0].status === "fulfilled" ? results[0].value : null;
  const retention = results[1].status === "fulfilled" ? results[1].value : null;
  const push = results[2].status === "fulfilled" ? results[2].value : null;
  $("#system-content").className = "system-grid";
  $("#system-content").innerHTML =
    `<section class="panel panel-pad"><h2>Instalação</h2>${dl([
      ["Servidor", window.location.host],
      ["Versão", update?.currentVersion || "Indisponível"],
      ["Última versão", update?.latestVersion || "Indisponível"],
      [
        "Atualização",
        update
          ? update.serverUpdateAvailable
            ? "Disponível no aplicativo"
            : "Servidor atualizado"
          : "Consulta indisponível",
      ],
      [
        "Notificações",
        push
          ? push.configured
            ? "Configuradas"
            : "Não configuradas"
          : "Indisponível",
      ],
    ])}</section><section class="panel panel-pad"><h2>Armazenamento e acesso</h2>${dl(
      [
        [
          "Retenção de mídia",
          retention ? `${retention.maxAgeDays} dias` : "Indisponível",
        ],
        [
          "Limite de mídia",
          retention ? `${retention.maxStorageGB} GB` : "Indisponível",
        ],
        ["Permissão desta sessão", "Apenas consulta"],
        ["Validade da sessão", "12 horas"],
      ],
    )}<p class="notice" style="margin-top:20px">Gerencie câmeras, regras, áreas e atualizações pelo aplicativo.</p></section>`;
}
function dl(rows: [string, string][]) {
  return `<dl class="data-list">${rows.map(([name, value]) => `<div><dt>${e(name)}</dt><dd>${e(value)}</dd></div>`).join("")}</dl>`;
}
function closeDetails() {
  dialogVersion++;
  modalController.abort();
  modalController = new AbortController();
  modalCleanups.splice(0).forEach((fn) => fn());
  $("#detail-body").replaceChildren();
}
function openDetails(title: string, kicker: string) {
  closeDetails();
  $("#detail-title").textContent = title;
  $("#detail-kicker").textContent = kicker;
  $("#detail-body").innerHTML =
    '<div class="loading"><span class="spinner"></span>Carregando…</div>';
  if (!dialog.open) dialog.showModal();
  return dialogVersion;
}
async function cameraDetails(id: string) {
  const c = cameras.find((c) => c.id === id);
  if (!c) return;
  openDetails(c.name, "CÂMERA");
  $("#detail-body").innerHTML =
    `<div class="media-stage"><video id="live-video" autoplay muted controls playsinline hidden></video><img id="camera-image" alt="Imagem da câmera" hidden/><span id="camera-loading">Carregando imagem…</span></div><p id="live-status" class="notice" role="status">Conectando…</p><div class="media-actions"><button id="show-image">Consultar imagem</button><button id="restart-live">Reconectar vídeo</button></div>${dl(
      [
        [
          "Estado",
          c.setupStatus === "ready"
            ? "Disponível"
            : c.setupError || c.setupStatus,
        ],
        ["Endereço", `${c.host}:${c.port}`],
      ],
    )}<div class="section-title"><h2>Regras desta câmera</h2></div>${
      rules
        .filter((r) => r.cameraId === id)
        .map(
          (r) =>
            `<button class="activity-row" data-rule="${e(r.id)}"><span class="tile-icon">${icon("rule")}</span><span class="activity-main"><strong>${e(r.name)}</strong><small>${e(schedule(r))}</small></span>${icon("arrow")}</button>`,
        )
        .join("") || '<p class="muted">Nenhuma regra cadastrada.</p>'
    }`;
  const video = $<HTMLVideoElement>("#live-video");
  const image = $<HTMLImageElement>("#camera-image");
  const status = $("#live-status");
  const loading = $("#camera-loading");
  const controller = new AbortController();
  let stop: () => void = () => {};
  let closed = false;
  let playing = false;
  let imageURL = "";
  let imageTimer: ReturnType<typeof setTimeout>;
  const updateImage = async () => {
    if (closed) return;
    if (!playing && !document.hidden) {
      try {
        const blob = await api.blob(
          `/cameras/${key(id)}/snapshot`,
          controller.signal,
        );
        if (closed) return;
        const next = URL.createObjectURL(blob);
        image.src = next;
        image.hidden = playing;
        loading.hidden = true;
        if (imageURL) URL.revokeObjectURL(imageURL);
        imageURL = next;
      } catch {
        if (!closed && !imageURL)
          loading.textContent = "Imagem indisponível. Tentando novamente…";
      }
    }
    if (!closed) imageTimer = setTimeout(updateImage, 5000);
  };
  const connect = () => {
    stop();
    playing = false;
    video.hidden = true;
    image.hidden = !imageURL;
    try {
      stop = live(
        api,
        id,
        video,
        (text) => {
          if (!closed) status.textContent = text;
        },
        (ready) => {
          if (closed) return;
          playing = ready;
          video.hidden = !ready;
          image.hidden = ready || !imageURL;
        },
      );
    } catch {
      status.textContent =
        "Exibindo imagens atualizadas a cada 5 segundos. Vídeo indisponível neste navegador.";
    }
  };
  void updateImage();
  connect();
  modalCleanups.push(() => {
    closed = true;
    controller.abort();
    clearTimeout(imageTimer);
    stop();
    if (imageURL) URL.revokeObjectURL(imageURL);
  });
  $("#restart-live").onclick = connect;
  $("#show-image").onclick = () => {
    stop();
    playing = false;
    video.hidden = true;
    image.hidden = !imageURL;
    status.textContent = "Imagens atualizadas a cada 5 segundos · sem áudio.";
  };
}
async function eventDetails(id: string) {
  const version = openDetails("Evento", "ACONTECIMENTO");
  try {
    const ev = await api.get<Event>(
      `/events/${key(id)}`,
      modalController.signal,
    );
    if (version !== dialogVersion) return;
    $("#detail-title").textContent = eventName(ev);
    $("#detail-body").innerHTML =
      `${ev.source === "tracking" ? "" : `<div class="media-stage" id="event-media"><span>Carregando imagem…</span></div>`}${dl([["Origem", eventSource(ev)], ["Quando", date(ev.occurredAt)], ["Status", ev.acknowledgedAt ? `Reconhecido em ${date(ev.acknowledgedAt)}` : "Não reconhecido"], ...(ev.source === "tracking" ? [] : [["Confiança", `${Math.round(ev.confidence * 100)}%`] as [string, string]])])}<div class="media-actions">${ev.clipStatus === "ready" ? '<button id="play-clip" class="primary">Reproduzir clipe</button>' : ""}</div>${ev.source !== "tracking" && ev.clipStatus !== "ready" ? `<p class="notice">${ev.clipStatus === "processing" ? "Clipe em processamento. Abra o evento novamente em alguns instantes." : ev.clipStatus === "failed" ? "Não foi possível preparar o clipe." : "Nenhum clipe associado a este evento."}</p>` : ""}`;
    if (ev.source !== "tracking") {
      if (ev.snapshotPath)
        try {
          const blob = await api.blob(
            `/events/${key(id)}/snapshot`,
            modalController.signal,
          );
          if (version !== dialogVersion) return;
          const url = URL.createObjectURL(blob);
          modalCleanups.push(() => URL.revokeObjectURL(url));
          $("#event-media").innerHTML =
            `<img src="${url}" alt="Imagem do evento"/>`;
        } catch {
          if (version === dialogVersion)
            $("#event-media").textContent = "Imagem indisponível.";
        }
      else $("#event-media").textContent = "Sem imagem associada.";
    }
    if (version === dialogVersion && $("#play-clip"))
      $("#play-clip").onclick = async () => {
        const button = $<HTMLButtonElement>("#play-clip");
        button.disabled = true;
        button.textContent = "Carregando clipe…";
        try {
          const blob = await api.blob(
            `/events/${key(id)}/clip`,
            modalController.signal,
          );
          if (version !== dialogVersion) return;
          const url = URL.createObjectURL(blob);
          modalCleanups.push(() => URL.revokeObjectURL(url));
          $("#event-media").innerHTML =
            `<video src="${url}" controls autoplay playsinline></video>`;
          button.textContent = "Clipe carregado";
        } catch {
          if (version === dialogVersion) {
            button.disabled = false;
            button.textContent = "Tentar carregar clipe novamente";
          }
        }
      };
  } catch (error) {
    if (version === dialogVersion)
      $("#detail-body").textContent =
        error instanceof Error ? error.message : "Evento indisponível.";
  }
}
async function personDetails(id: string) {
  const p = people.find((p) => p.id === id);
  if (!p) return;
  const version = openDetails(p.name, "HISTÓRICO DE LOCALIZAÇÃO");
  try {
    const history = await api.get<Location[]>(
      `/users/${key(id)}/history?limit=100`,
      modalController.signal,
    );
    if (version !== dialogVersion) return;
    $("#detail-body").innerHTML = `${dl([
      ["Última atualização", date(p.lastLocatedAt)],
      [
        "Precisão informada",
        p.lastAccuracy ? `${Math.round(p.lastAccuracy)} m` : "Sem registro",
      ],
    ])}${history.length ? '<div id="history-map" class="map" style="height:300px;margin-top:20px"></div>' : ""}<ol class="history-list">${history.map((l) => `<li><time>${date(l.occurredAt)}</time>${e(l.address || "Endereço indisponível")}<small> · precisão ${Math.round(l.accuracy)} m</small></li>`).join("")}</ol>${history.length ? "" : empty("Sem histórico", "Nenhuma localização recebida para este aparelho.")}`;
    if (history.length) {
      const { familyMap } = await import("./lib/map");
      if (version === dialogVersion)
        modalCleanups.push(familyMap($("#history-map"), [p], places, history));
    }
  } catch (error) {
    if (version === dialogVersion)
      $("#detail-body").textContent =
        error instanceof Error ? error.message : "Histórico indisponível.";
  }
}
async function ruleDetails(id: string) {
  const r = rules.find((r) => r.id === id);
  if (!r) return;
  openDetails(r.name, "REGRA DE MONITORAMENTO");
  $("#detail-body").innerHTML = `${dl([
    ["Câmera", cameraName(r.cameraId)],
    ["Detectores", r.detectorTypes.map((d) => labels[d] || d).join(", ")],
    ["Horário", schedule(r)],
    ["Intervalo entre alertas", `${r.cooldownSeconds} s`],
    ["Confirmações", String(r.confirmations)],
    ...(r.motion
      ? ([
          ["Movimento persistente", `${r.motion.minDurationSeconds} s`],
          [
            "Mudança mínima na região",
            `${Math.round(r.motion.minChangedFraction * 100)}%`,
          ],
        ] as [string, string][])
      : []),
  ])}<div class="rule-actions">${actionPills(r)}</div>${r.motion ? `<div class="section-title"><h2>Região monitorada</h2></div><div class="media-stage"><img data-snapshot="${e(r.cameraId)}" alt="Região configurada na câmera" hidden/><span class="region" style="left:${r.motion.region.x * 100}%;top:${r.motion.region.y * 100}%;width:${r.motion.region.width * 100}%;height:${r.motion.region.height * 100}%"></span></div><p class="notice">A região corresponde ao enquadramento configurado. Movimento na região não identifica postura nem risco médico.</p>` : ""}`;
  void snapshots($("#detail-body"), modalController.signal, modalCleanups);
}
async function refresh(force = false) {
  if (refreshing || !api.token || document.hidden) return;
  refreshing = true;
  $<HTMLButtonElement>("#refresh").disabled = true;
  const token = api.token;
  const response = await Promise.allSettled([
    api.get<Camera[]>("/cameras", alive.signal),
    api.get<Event[]>("/events?limit=200", alive.signal),
    api.get<Person[]>("/users", alive.signal),
    api.get<Place[]>("/places", alive.signal),
    api.get<Rule[]>("/rules", alive.signal),
  ]);
  if (token !== api.token) {
    refreshing = false;
    return;
  }
  const failures = response.filter((r) => r.status === "rejected");
  response.forEach((r, i) => {
    if (r.status !== "fulfilled") return;
    const value = r.value || [];
    if (i === 0) cameras = value as Camera[];
    if (i === 1) events = value as Event[];
    if (i === 2) people = value as Person[];
    if (i === 3) places = value as Place[];
    if (i === 4) rules = value as Rule[];
  });
  const next = JSON.stringify([cameras, events, people, places, rules]);
  if (next !== signature || !hasLoaded || force) {
    signature = next;
    hasLoaded = true;
    render();
  }
  $("#connection-text").textContent = failures.length
    ? "Conexão parcial · tentando novamente"
    : "Conectado ao seu servidor";
  $("#global-error").hidden = !failures.length;
  $("#global-error").textContent = failures.length
    ? "Alguns dados não puderam ser atualizados. Você pode tentar novamente pelo botão de atualizar."
    : "";
  if (!failures.length)
    $("#last-sync").textContent =
      `Atualizado às ${clock(new Date().toISOString())}`;
  $("#count-cameras").textContent = String(cameras.length);
  $("#count-events").textContent = String(
    events.filter((ev) => !ev.acknowledgedAt).length || "",
  );
  $<HTMLButtonElement>("#refresh").disabled = false;
  refreshing = false;
}
function enter() {
  alive.abort();
  alive = new AbortController();
  $("#login").hidden = true;
  $("#application").hidden = false;
  hasLoaded = false;
  page = messages[window.location.hash.slice(1)]
    ? window.location.hash.slice(1)
    : "overview";
  void refresh(true);
}
function leave(message = "") {
  alive.abort();
  api.clear();
  disposeView();
  dialog.close();
  closeDetails();
  cameras = [];
  events = [];
  people = [];
  places = [];
  rules = [];
  signature = "";
  hasLoaded = false;
  main.replaceChildren();
  $("#application").hidden = true;
  $("#login").hidden = false;
  $("#login-error").textContent = message;
  $("#login-error").hidden = !message;
  $("#password").focus();
}
$("#login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = $<HTMLButtonElement>("#login-submit");
  button.disabled = true;
  $("#login-error").hidden = true;
  try {
    await api.login($<HTMLInputElement>("#password").value);
    $<HTMLInputElement>("#password").value = "";
    enter();
  } catch (error) {
    $("#login-error").textContent =
      error instanceof Error ? error.message : "Não foi possível entrar.";
    $("#login-error").hidden = false;
  } finally {
    button.disabled = false;
  }
});
$("#logout").onclick = () => {
  void api.logout();
  leave();
};
$("#refresh").onclick = () => void refresh(true);
$("#detail-close").onclick = () => dialog.close();
dialog.addEventListener("close", closeDetails);
dialog.addEventListener("click", (event) => {
  if (event.target === dialog) {
    const r = dialog.getBoundingClientRect();
    if (
      event.clientX < r.left ||
      event.clientX > r.right ||
      event.clientY < r.top ||
      event.clientY > r.bottom
    )
      dialog.close();
  }
});
window.addEventListener("viewer-expired", () =>
  leave("Sua sessão expirou. Entre novamente."),
);
window.addEventListener("hashchange", () => {
  const next = window.location.hash.slice(1);
  if (messages[next]) {
    page = next;
    if (api.token && hasLoaded) render();
  }
});
document.addEventListener("click", (event) => {
  const el = (event.target as Element).closest<HTMLElement>(
    "[data-camera],[data-event],[data-person],[data-rule]",
  );
  if (!el) return;
  if (el.dataset.camera) void cameraDetails(el.dataset.camera);
  if (el.dataset.event) void eventDetails(el.dataset.event);
  if (el.dataset.person) void personDetails(el.dataset.person);
  if (el.dataset.rule) void ruleDetails(el.dataset.rule);
});
try {
  document.documentElement.dataset.theme =
    localStorage.getItem("valkyris-viewer-theme") || "auto";
} catch {}
$("#theme").onclick = () => {
  const current = document.documentElement.dataset.theme;
  const dark =
    current === "dark" ||
    (current === "auto" && matchMedia("(prefers-color-scheme:dark)").matches);
  document.documentElement.dataset.theme = dark ? "light" : "dark";
  try {
    localStorage.setItem(
      "valkyris-viewer-theme",
      document.documentElement.dataset.theme!,
    );
  } catch {}
};
setInterval(() => void refresh(), 15000);
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) void refresh();
});
window.addEventListener("pagehide", () => {
  disposeView();
  closeDetails();
  alive.abort();
});
if (api.token) enter();
