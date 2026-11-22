// FCM HTTP v1 from the edge (data-only, high priority). Same message format as server/worker/push.py.
const THRESHOLDS = [10, 30, 50, 70];
let cached: { token: string; exp: number; project: string } | null = null;

function account(): any | null {
  const raw = (Deno.env.get("FIREBASE_SERVICE_ACCOUNT") ?? "").trim();
  if (!raw) return null;
  try { return JSON.parse(raw.startsWith("{") ? raw : atob(raw)); } catch { return null; }
}
export const pushEnabled = () => account() !== null;

const b64url = (b: ArrayBuffer | Uint8Array | string) => {
  const bytes = typeof b === "string" ? new TextEncoder().encode(b) : new Uint8Array(b as ArrayBuffer);
  let s = ""; for (const x of bytes) s += String.fromCharCode(x);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

async function accessToken(): Promise<{ token: string; project: string } | null> {
  const sa = account();
  if (!sa) return null;
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.exp - 120 > now) return cached;
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const unsigned = `${b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }))}.${b64url(JSON.stringify({
    iss: sa.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600 }))}`;
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  const r = await fetch("https://oauth2.googleapis.com/token", { method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${unsigned}.${b64url(sig)}` });
  if (!r.ok) { console.warn("fcm oauth", r.status); return null; }
  const j = await r.json();
  cached = { token: j.access_token, exp: now + (j.expires_in ?? 3600), project: sa.project_id };
  return cached;
}

/** @returns "ok" | "dead" (token unregistered) | "error" */
export async function send(message: Record<string, unknown>): Promise<string> {
  const t = await accessToken();
  if (!t) return "error";
  const r = await fetch(`https://fcm.googleapis.com/v1/projects/${t.project}/messages:send`, {
    method: "POST", headers: { Authorization: `Bearer ${t.token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ message }) });
  if (r.ok) return "ok";
  const body = await r.text();
  if (r.status === 404 || body.includes("UNREGISTERED")) return "dead";
  console.warn("fcm", r.status, body.slice(0, 200));
  return "error";
}

const android = (collapse?: string) => ({ priority: "high", ttl: "3600s", ...(collapse ? { collapse_key: collapse.slice(0, 60) } : {}) });
const egp = (n: number) => Math.round(n).toLocaleString("en-US");

export type DealEvent = { kind: string; id: string; store: string; title: string; current_price: number; original_price: number | null; pct: number };

export async function dealPushes(events: DealEvent[], storeNames: Record<string, string>): Promise<number> {
  if (!pushEnabled() || !events.length) return 0;
  let sent = 0;
  const low: string[] = [];
  for (const e of events.sort((a, b) => b.pct - a.pct).slice(0, 8)) {
    const pct = Number(e.pct), store = storeNames[e.store] ?? e.store, again = e.kind === "drop" ? "نزل تاني! " : "";
    if (pct < 30) { low.push(`-${Math.floor(pct)}% ${e.title.slice(0, 40)} — ${egp(e.current_price)} ج.م`); continue; }
    const level = pct >= 70 ? "urgent" : pct >= 50 ? "super" : "hot";
    const title = level === "urgent" ? `🚨 ${again}الحق! خصم ${Math.floor(pct)}% على ${store}`
      : level === "super" ? `⚡ ${again}سوبر عرض -${Math.floor(pct)}% — قبل ما يخلص` : `🔥 ${again}عرض ساخن -${Math.floor(pct)}% على ${store}`;
    const condition = THRESHOLDS.filter((t) => t <= pct).map((t) => `'deals_min_${t}' in topics`).join(" || ");
    if (await send({ condition, android: android(e.id), data: { type: "deal", level, title,
      body: `${e.title.slice(0, 110)}\n${egp(e.current_price)} ج.م` + (e.original_price ? ` بدل ${egp(e.original_price)}` : ` · خصم ${Math.floor(pct)}% أو أكتر`), deal_id: e.id,
      pct: String(pct), price: String(Math.round(Number(e.current_price))), sent_at: String(Date.now()) } }) === "ok") sent++;
  }
  if (low.length) {
    await send({ topic: "deals_min_10", android: android("low_summary"),
      data: { type: "deal", level: "low", title: `خصومات جديدة: ${low.length}+`, body: low.slice(0, 6).join("\n") } });
  }
  return sent;
}

/** Personal push to specific device tokens. Returns tokens that are no longer valid. */
export async function toTokens(tokens: string[], title: string, body: string, route?: string, tag?: string): Promise<string[]> {
  const dead: string[] = [];
  if (!pushEnabled()) return dead;
  for (const token of tokens) {
    const data: Record<string, string> = { type: "personal", level: "hot", title, body };
    if (route) data.route = route;
    if (tag) data.tag = tag;
    if (await send({ token, android: android(tag), data }) === "dead") dead.push(token);
  }
  return dead;
}
