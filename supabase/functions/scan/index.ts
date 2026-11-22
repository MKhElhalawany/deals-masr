// 24/7 deal scanner on Supabase Edge Functions (free plan, no VM, no phone involved).
// Called only by the database: pg_cron every 30 s per store, plus triggers for searches / SHEIN quotes / orders.
// If the optional VM worker (server/worker) is alive, this function steps aside to avoid double work.
import { Blocked, STORES } from "./parsers.ts";
import { dealPushes, pushEnabled, toTokens } from "./push.ts";
import { goodsId, parseShein } from "./shein.ts";

const URL_ = Deno.env.get("SUPABASE_URL")!.replace(/\/$/, "");
const KEY = Deno.env.get("SERVICE_KEY") || Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const CRON_SECRET = Deno.env.get("CRON_SECRET") ?? "";
const STORE_NAMES = Object.fromEntries(Object.entries(STORES).map(([k, v]) => [k, v.name]));
const UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";

// ───────── PostgREST with the server key (key exists only as a function secret)
async function rest(method: string, path: string, body?: unknown, prefer = "return=minimal"): Promise<any> {
  const r = await fetch(`${URL_}/rest/v1/${path}`, { method, body: body === undefined ? undefined : JSON.stringify(body),
    headers: { apikey: KEY, Authorization: `Bearer ${KEY}`, "Content-Type": "application/json", Prefer: prefer } });
  if (!r.ok) throw new Error(`${method} ${path.split("?")[0]} → ${r.status}: ${(await r.text()).slice(0, 200)}`);
  const t = await r.text();
  return t ? JSON.parse(t) : null;
}
const select = (path: string) => rest("GET", path);
const status = (store: string, ok: boolean, message: string, seen = 0) =>
  rest("POST", "scraper_status", [{ store, ok, message: message.slice(0, 300), deals_seen: seen, last_run: new Date().toISOString() }],
    "return=minimal,resolution=merge-duplicates");

let cfgCache: { at: number; v: Record<string, any> } | null = null;
async function config() {
  if (cfgCache && Date.now() - cfgCache.at < 120_000) return cfgCache.v;
  const rows = await select("app_config?select=key,value");
  cfgCache = { at: Date.now(), v: Object.fromEntries(rows.map((r: any) => [r.key, r.value])) };
  return cfgCache.v;
}

/** true if the VM worker reported in the last 2 minutes */
async function vmAlive(): Promise<boolean> {
  const r = await select("scraper_status?select=last_run&store=eq.worker");
  return !!r[0] && Date.now() - Date.parse(r[0].last_run) < 120_000;
}

async function fetchPage(url: string): Promise<[number, string]> {
  const r = await fetch(url, { redirect: "follow", signal: AbortSignal.timeout(25_000), headers: {
    "User-Agent": UA, "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7" } });
  return [r.status, await r.text()];
}

// ───────── deals in → DB (single RPC) → push
async function ingest(items: unknown[]): Promise<{ accepted: number; events: any[]; notifications: any[] }> {
  const min = Number((await config()).min_discount_pct ?? 10);
  return await rest("POST", "rpc/ingest_deals", { p_items: items, p_min_pct: min }, "return=representation");
}

async function personalPushes(notes: { user_id: string; deal_id: string | null; title: string; body: string }[]) {
  if (!notes.length || !pushEnabled()) return;
  const users = [...new Set(notes.map((n) => n.user_id))];
  const tokens = await select(`device_tokens?select=token,user_id&enabled=eq.true&user_id=in.(${users.join(",")})`);
  for (const t of tokens) {
    for (const n of notes.filter((x) => x.user_id === t.user_id).slice(0, 3)) {
      const dead = await toTokens([t.token], n.title, n.body, n.deal_id ? `product/${n.deal_id}` : undefined, n.deal_id ?? undefined);
      if (dead.length) await rest("DELETE", `device_tokens?token=eq.${encodeURIComponent(dead[0])}`);
    }
  }
}

async function scanStore(store: string) {
  const spec = STORES[store];
  const cfg = await config();
  if (!spec || !(cfg.scrape_stores ?? Object.keys(STORES)).includes(store)) return { skipped: "store disabled" };
  // backoff after a block: 10 minutes
  const prev = (await select(`scraper_status?select=ok,message,last_run&store=eq.${store}`))[0];
  if (prev && !prev.ok && String(prev.message).startsWith("⛔") && Date.now() - Date.parse(prev.last_run) < 10 * 60_000) return { skipped: "backoff" };

  const kws: string[] = cfg.scrape_keywords ?? [];
  const step = Math.floor(Date.now() / 30_000);
  let url: string, label: string;
  if (spec.deals.length && (step % 2 === 1 || !kws.length)) { url = spec.deals[Math.floor(step / 2) % spec.deals.length]; label = "صفحة العروض"; }
  else if (kws.length) { const kw = kws[Math.floor(step / (spec.deals.length ? 2 : 1)) % kws.length]; url = spec.search(kw); label = kw; }
  else return { skipped: "no targets" };

  const t0 = Date.now();
  try {
    const [code, html] = await fetchPage(url);
    const t1 = Date.now();
    if ([403, 429, 503].includes(code)) throw new Blocked(`HTTP ${code}`);
    const [items, cards] = spec.parse(html);
    const t2 = Date.now();
    const res = await ingest(items);
    const t3 = Date.now();
    const pushed = await dealPushes(res.events, STORE_NAMES);
    await personalPushes(res.notifications);
    const t4 = Date.now();
    await status(store, cards > 0, cards > 0
      ? `✓ ${res.accepted} عرض من ${cards} منتج (${label}) · جديد/نزل: ${res.events.length} · تحميل ${t1 - t0}ms · حفظ ${t3 - t2}ms · push ${t4 - t3}ms (${pushed})`
      : `⚠️ الصفحة اتقرت بس مفيهاش منتجات (${label}) — HTTP ${code}`, res.accepted);
    if (step % 120 === 0 && store === "amazon") await rest("POST", "rpc/expire_stale_deals", { p_hours: 36 });
    return { store, label, code, cards, accepted: res.accepted, events: res.events.length, fetch_ms: t1 - t0, parse_ms: t2 - t1, save_ms: t3 - t2, push_ms: t4 - t3 };
  } catch (e) {
    const blocked = e instanceof Blocked;
    await status(store, false, blocked ? `⛔ المتجر حاجب سيرفر Supabase (${e.message}) — هنحاول تاني بعد 10 دقايق` : `⚠️ ${String(e).slice(0, 250)}`);
    return { store, error: String(e) };
  }
}

async function runSearch(id: number) {
  const r = (await select(`search_requests?select=id,query,stores,status&id=eq.${id}`))[0];
  if (!r || r.status !== "pending") return { skipped: true };
  await rest("PATCH", `search_requests?id=eq.${id}`, { status: "running" });
  const cfg = await config();
  const stores = (r.stores?.length ? r.stores : cfg.scrape_stores ?? Object.keys(STORES)).filter((s: string) => STORES[s]);
  let total = 0;
  await Promise.all(stores.map(async (s: string) => {
    try {
      const [code, html] = await fetchPage(STORES[s].search(r.query));
      if (code !== 200) return;
      const res = await ingest(STORES[s].parse(html)[0]);
      total += res.accepted;
      await dealPushes(res.events, STORE_NAMES);
      await personalPushes(res.notifications);
    } catch (e) { console.warn("search", s, String(e)); }
  }));
  await rest("PATCH", `search_requests?id=eq.${id}`, { status: "done", message: `${total} عرض`, done_at: new Date().toISOString() });
  return { total };
}

async function runShein(id: number) {
  const q = (await select(`shein_quotes?select=id,url,kind,status&id=eq.${id}`))[0];
  if (!q || q.status !== "pending") return { skipped: true };
  await rest("PATCH", `shein_quotes?id=eq.${id}`, { status: "running", updated_at: new Date().toISOString() });
  let patch: Record<string, unknown>;
  try {
    const gid = q.kind === "product" ? goodsId(q.url) : null;
    if (!gid) throw new Error("التسعير التلقائي للباج/اللينكات المختصرة محتاج السيرفر الكامل");
    let last: unknown = null, p: ReturnType<typeof parseShein> | null = null;
    for (const base of ["https://sa.shein.com", "https://ar.shein.com"]) {
      try { const [, html] = await fetchPage(`${base}/product-p-${gid}.html?currency=SAR`); p = parseShein(html, gid); break; } catch (e) { last = e; }
    }
    if (!p) throw last ?? new Error("تعذر فتح صفحة المنتج");
    patch = { status: "priced", goods_id: gid, title: p.title.slice(0, 300), image_url: p.image_url, retail_sar: p.retail_sar, error: null };
  } catch (e) {
    patch = { status: "failed", error: `${e instanceof Error ? e.message : e} — اكتب السعر الأصلي بالريال من صفحة المنتج`.slice(0, 300) };
  }
  patch.updated_at = new Date().toISOString();
  await rest("PATCH", `shein_quotes?id=eq.${id}`, patch);
  return patch;
}

async function runOrder(id: string) {
  if (!pushEnabled()) return { skipped: "push off" };
  const o = (await select(`orders?select=id,user_id,status,customer_name,total_egp&id=eq.${encodeURIComponent(id)}`))[0];
  if (!o) return { skipped: true };
  if (o.status === "confirmed") {
    const toks = (await select(`device_tokens?select=token&enabled=eq.true&user_id=eq.${o.user_id}`)).map((t: any) => t.token);
    await toTokens(toks, "تم تأكيد طلبك 🎉", `طلب #${o.id} اتأكد وهيوصلك خلال 25–35 يوم`, `order/${o.id}`, `c${o.id}`);
  } else if (o.status === "submitted") {
    const admins = (await select("profiles?select=id&role=eq.admin")).map((a: any) => a.id);
    if (admins.length) {
      const toks = (await select(`device_tokens?select=token&enabled=eq.true&user_id=in.(${admins.join(",")})`)).map((t: any) => t.token);
      await toTokens(toks, `🛍️ طلب جديد من ${o.customer_name}`, `${Math.round(Number(o.total_egp)).toLocaleString("en-US")} ج.م`, "admin_orders", `n${o.id}`);
    }
  }
  return { ok: true };
}

function sameSecret(a: string, b: string) {
  if (!a || a.length !== b.length) return false;
  let d = 0; for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

Deno.serve(async (req) => {
  if (!CRON_SECRET || !sameSecret(req.headers.get("x-cron-secret") ?? "", CRON_SECRET)) return new Response("forbidden", { status: 403 });
  const body = await req.json().catch(() => ({}));
  try {
    let out: unknown;
    if (body.job === "order") out = await runOrder(String(body.id));   // pushes: needed even when the VM worker runs
    else if (await vmAlive()) out = { skipped: "vm worker active" };
    else if (body.job === "scan") {
      out = await scanStore(String(body.store));
      if (body.store === "amazon") await status("edge", true, `شغال ✓ ${new Date().toISOString().slice(11, 19)} UTC · Supabase Edge كل 30 ث · push ${pushEnabled() ? "on" : "off"}`);
    } else if (body.job === "search") out = await runSearch(Number(body.id));
    else if (body.job === "shein") out = await runShein(Number(body.id));
    else out = { error: "unknown job" };
    return Response.json(out);
  } catch (e) {
    console.error(e);
    return Response.json({ error: String(e) }, { status: 500 });
  }
});
