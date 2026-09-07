export interface Camera {
  id: string;
  name: string;
  icon: string;
  host: string;
  port: number;
  enabled: boolean;
  setupStatus: string;
  setupError?: string;
  capabilities: { audio: boolean; ptz: boolean; zoom: boolean };
}
export interface Event {
  id: string;
  cameraId?: string;
  subjectId?: string;
  source?: string;
  type: string;
  confidence: number;
  occurredAt: string;
  acknowledgedAt?: string;
  snapshotPath?: string;
  clipPath?: string;
  clipStatus: string;
  metadata: Record<string, unknown>;
}
export interface Person {
  id: string;
  name: string;
  avatarData?: string;
  lastLatitude?: number;
  lastLongitude?: number;
  lastAccuracy?: number;
  lastLocatedAt?: string;
}
export interface Place {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
  radiusMeters: number;
  enabled: boolean;
}
export interface Rule {
  id: string;
  name: string;
  cameraId: string;
  detectorTypes: string[];
  confirmations: number;
  cooldownSeconds: number;
  enabled: boolean;
  schedule: { days: number[]; start: string; end: string; timezone: string };
  actions: { record: boolean; notify: boolean; alarm: boolean };
  motion?: {
    region: { x: number; y: number; width: number; height: number };
    minDurationSeconds: number;
    minChangedFraction: number;
  };
}
export interface Location {
  latitude: number;
  longitude: number;
  accuracy: number;
  address?: string;
  occurredAt: string;
}
export interface Update {
  currentVersion: string;
  latestVersion: string;
  serverUpdateAvailable: boolean;
}
export const labels: Record<string, string> = {
  motion: "Movimento",
  person: "Pessoa",
  tamper: "Câmera obstruída",
  baby_cry: "Choro de bebê",
  crying: "Choro",
  scream: "Grito",
  glass_break: "Vidro quebrando",
  smoke_alarm: "Alarme de fumaça",
  fire_alarm: "Alarme de incêndio",
  siren: "Sirene",
  doorbell: "Campainha",
  knock: "Batida",
  dog_bark: "Latido",
  place_entered: "Entrada na área",
  place_exited: "Saída da área",
};
export const escape = (value: unknown): string =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ]!,
  );
export const date = (value?: string): string =>
  value && !Number.isNaN(Date.parse(value))
    ? new Intl.DateTimeFormat("pt-BR", {
        dateStyle: "short",
        timeStyle: "short",
      }).format(new Date(value))
    : "Sem registro";
export const clock = (value: string): string =>
  !Number.isNaN(Date.parse(value))
    ? new Intl.DateTimeFormat("pt-BR", {
        hour: "2-digit",
        minute: "2-digit",
      }).format(new Date(value))
    : "—";
export const key = (value: string) => encodeURIComponent(value);
const sessionKey = "valkyris-viewer-session";
export class API {
  token = "";
  constructor() {
    try {
      this.token = sessionStorage.getItem(sessionKey) || "";
    } catch {}
  }
  clear() {
    this.token = "";
    try {
      sessionStorage.removeItem(sessionKey);
    } catch {}
  }
  async login(password: string) {
    const response = await fetch("/api/v1/login", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Accept-Language": "pt-BR",
      },
      body: JSON.stringify({
        password,
        deviceName: "Navegador de consulta",
        locale: "pt-BR",
        readOnly: true,
      }),
      signal: AbortSignal.timeout(15000),
    });
    const body = await response.json();
    if (!response.ok || !body.success)
      throw new Error(body.message || "Não foi possível entrar.");
    if (!body.data?.readOnly)
      throw new Error(
        "Atualize o servidor para habilitar o painel de consulta.",
      );
    this.token = body.data.token;
    try {
      sessionStorage.setItem(sessionKey, this.token);
    } catch {}
  }
  async response(path: string, signal?: AbortSignal): Promise<Response> {
    if (!path.startsWith("/") || path.startsWith("//"))
      throw new Error("Caminho inválido.");
    const response = await fetch("/api/v1" + path, {
      headers: {
        Authorization: `Bearer ${this.token}`,
        "Accept-Language": "pt-BR",
      },
      cache: "no-store",
      signal: signal
        ? AbortSignal.any([signal, AbortSignal.timeout(30000)])
        : AbortSignal.timeout(30000),
    });
    if (response.status === 401) {
      this.clear();
      window.dispatchEvent(new CustomEvent("viewer-expired"));
      throw new Error("Sessão expirada. Entre novamente.");
    }
    if (!response.ok) {
      let message = "Não foi possível carregar os dados.";
      try {
        const body = await response.json();
        message = body.message || message;
      } catch {}
      throw new Error(message);
    }
    return response;
  }
  async get<T>(path: string, signal?: AbortSignal): Promise<T> {
    const response = await this.response(path, signal);
    const body = await response.json();
    if (!body.success) throw new Error(body.message || "Resposta inválida.");
    return body.data;
  }
  async blob(path: string, signal?: AbortSignal) {
    return (await this.response(path, signal)).blob();
  }
  async logout() {
    const token = this.token;
    this.clear();
    try {
      await fetch("/api/v1/viewer-session", {
        method: "DELETE",
        headers: { Authorization: `Bearer ${token}` },
        signal: AbortSignal.timeout(5000),
      });
    } catch {}
  }
}
