// 24/7 deal scanner on Supabase Edge Functions (free plan, no VM, no phone involved).
// Called only by the database: pg_cron every 30 s per store, plus triggers for searches / SHEIN quotes / orders.
// If the optional VM worker (server/worker) is alive, this function steps aside to avoid double work.
import { amazonCandidates, Blocked, generic, lastNoDeal, parseAmazonProduct, parseTownTeamJson, STORES, townTeamJsonUrl } from "./parsers.ts";
const checkedAsins = new Map<string, number>();   // product pages already checked (per isolate), avoid re-fetching
import { dealPushes, pushEnabled, toTokens } from "./push.ts";
import { goodsId, parseShein } from "./shein.ts";
import { categoryLinks, fingerprint, nextPage, Robots } from "./discover.ts";
import { audit } from "../_shared/audit.ts";
import { webOffers } from "../_shared/websearch.ts";
import * as WS from "../_shared/websearch.ts";
import { aiErrors } from "../_shared/gemini.ts";
import { parseTelegram } from "./telegram.ts";

const URL_ = Deno.env.get("SUPABASE_URL")!.replace(/\/$/, "");
const KEY = Deno.env.get("SERVICE_KEY") || Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const CRON_SECRET = Deno.env.get("CRON_SECRET") ?? "";
const STORE_NAMES = Object.fromEntries(Object.entries(STORES).map(([k, v]) => [k, v.name]));
const UAS = [
  "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
];

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

/** Mobile browser first, desktop as a second try if the store refuses or the connection drops. */
async function fetchPage(url: string): Promise<[number, string]> {
  let last: [number, string] = [0, ""], err: unknown = null;
  // Amazon's server HTML matches our parser in desktop layout; noon/jumia work best as mobile
  const order = url.includes("amazon.") ? [UAS[1], UAS[0]] : UAS;
  for (const ua of order) {
    try {
      const r = await fetch(url, { redirect: "follow", signal: AbortSignal.timeout(20_000), headers: {
        "User-Agent": ua, "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7" } });
      last = [r.status, await r.text()];
      if (![403, 429, 503].includes(r.status)) return last;
    } catch (e) { err = e; }
  }
  if (!last[0] && err) throw err;
  return last;
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

/** Amazon often shows "Deal" cards without the old price: verify ONE unseen candidate per run on its product page. */
async function verifyAmazonCandidate(items: unknown[]) {
  if (!amazonCandidates.length) return;
  const now = Date.now();
  for (const [k, t] of checkedAsins) if (now - t > 6 * 3600_000) checkedAsins.delete(k);
  const fresh = amazonCandidates.filter((c) => !checkedAsins.has(c.asin));
  const known = fresh.length ? new Set((await select(`deals?select=id&id=in.(${fresh.map((c) => `"amazon:${c.asin}"`).join(",")})`)).map((r: any) => r.id)) : new Set();
  const pick = fresh.find((c) => !known.has(`amazon:${c.asin}`));
  if (!pick) return;
  checkedAsins.set(pick.asin, now);
  try {
    const [pc, ph] = await fetchPage(`https://www.amazon.eg/dp/${pick.asin}?language=en_AE`);
    const v = pc === 200 ? parseAmazonProduct(ph) : null;
    if (v) {
      const id = `amazon:${pick.asin}`;
      for (let i = items.length - 1; i >= 0; i--) if ((items[i] as any).id === id) items.splice(i, 1);
      items.push({ id, store: "amazon", title: pick.title, url: `https://www.amazon.eg/dp/${pick.asin}`,
        image_url: pick.image_url, current_price: v.current, original_price: v.list });
    }
  } catch (_) { /* skip */ }
}

async function scanStore(store: string) {
  const spec = STORES[store];
  const cfg = await config();
  if (!spec || !(cfg.scrape_stores ?? Object.keys(STORES)).includes(store)) return { skipped: "store disabled" };
  // backoff after a block: 10 minutes
  const prev = (await select(`scraper_status?select=ok,message,last_run&store=eq.${store}`))[0];
  if (prev && !prev.ok && String(prev.message).startsWith("⛔") && Date.now() - Date.parse(prev.last_run) < (store === "jumia" ? 30 : 3) * 60_000) return { skipped: "backoff" };

  const kws: string[] = cfg.scrape_keywords ?? [];
  const step = Math.floor(Date.now() / 20_000);
  // each run: the store's deals page (if any) + one keyword search, fetched in parallel
  const targets: [string, string][] = [];
  if (store === "amazon") {   // Amazon rate-limits cloud IPs: one page per run, alternating deals page / keyword
    if (step % 2 === 0 || !kws.length) targets.push([spec.deals[0], "صفحة العروض"]);
    else { const kw = kws[Math.floor(step / 2) % kws.length]; targets.push([spec.search!(kw), kw]); }
  } else if (!spec.search) {  // category-only store (noon): two different category pages per run, same request rate as before
    const n = spec.deals.length;
    for (const i of n > 1 ? [step % n, (step + Math.floor(n / 2)) % n] : [0]) targets.push([spec.deals[i], "قسم " + new URL(spec.deals[i]).pathname.split("/").filter(Boolean).pop()]);
  } else {
    if (spec.deals.length) targets.push([spec.deals[step % spec.deals.length], "صفحة العروض"]);
    if (kws.length) { const kw = kws[step % kws.length]; targets.push([spec.search(kw), kw]); }
  }
  if (!targets.length) return { skipped: "no targets" };
  const label = targets.map((t) => t[1]).join(" + ");
  const t0 = Date.now();
  try {
    const pages = await Promise.allSettled(targets.map((t) => fetchPage(t[0])));
    const t1 = Date.now();
    const items: unknown[] = [];
    let cards = 0, code = 0, blockedCode = 0, firstErr: unknown = null;
    for (const p of pages) {
      if (p.status === "rejected") { firstErr ??= p.reason; continue; }
      const [c, html] = p.value;
      code = code || c;
      if ([403, 429, 503].includes(c)) { blockedCode = c; continue; }
      try { const [it, n] = spec.parse(html); items.push(...it); cards += n; } catch (e) { if (e instanceof Blocked) blockedCode = c || 403; else firstErr ??= e; }
    }
    if (!cards && blockedCode) throw new Blocked(`HTTP ${blockedCode}`);
    if (store === "amazon") await verifyAmazonCandidate(items);
    const t2 = Date.now();
    const res = await ingest(items);
    const t3 = Date.now();
    const pushed = await dealPushes(res.events, STORE_NAMES);
    await personalPushes(res.notifications);
    const t4 = Date.now();
    await status(store, cards > 0, cards > 0
      ? `✓ ${res.accepted} عرض من ${cards} منتج (${label}) · جديد/نزل: ${res.events.length} · تحميل ${t1 - t0}ms · حفظ ${t3 - t2}ms · push ${t4 - t3}ms (${pushed})`
      : `⚠️ الصفحة اتقرت بس مفيهاش منتجات (${label}) — HTTP ${code}`, res.accepted);
    if (step % 180 === 0) await rest("POST", "rpc/expire_stale_deals", { p_hours: 36 });
    return { store, label, code, cards, accepted: res.accepted, events: res.events.length, fetch_ms: t1 - t0, parse_ms: t2 - t1, save_ms: t3 - t2, push_ms: t4 - t3 };
  } catch (e) {
    const blocked = e instanceof Blocked;
    await status(store, false, blocked ? `⛔ المتجر حاجب سيرفر Supabase (${e.message}) — هنحاول تاني بعد شوية` : `⚠️ ${String(e).slice(0, 250)}`);
    return { store, error: String(e) };
  }
}

// ───────── discovery queue (amazon, noon, townteam): every category of the store, only discounted items saved
const QUEUE_STORES = new Set(["amazon", "noon", "townteam"]);
const ORIGINS: Record<string, string> = { amazon: "https://www.amazon.eg", noon: "https://www.noon.com", townteam: "https://townteam.com" };
const robotsMem = new Map<string, { at: number; r: Robots }>();

/** robots.txt per store: memory → DB cache (24 h) → one fetch. Unknown robots = scan nothing new (fail closed). */
async function robotsFor(store: string): Promise<Robots | null> {
  const m = robotsMem.get(store);
  if (m && Date.now() - m.at < 3600_000) return m.r;
  const row = (await select(`robots_cache?select=body,fetched_at&store=eq.${store}`))[0];
  let body: string | null = row && Date.now() - Date.parse(row.fetched_at) < 86400_000 ? row.body : null;
  if (body === null) {
    try {
      const r = await fetch(`${ORIGINS[store]}/robots.txt`, { signal: AbortSignal.timeout(15_000), headers: { "User-Agent": UAS[0] } });
      if (r.ok) {
        body = await r.text();
        await rest("POST", "robots_cache", [{ store, body: body.slice(0, 200_000), fetched_at: new Date().toISOString() }], "return=minimal,resolution=merge-duplicates");
      } else body = row?.body ?? null;
    } catch { body = row?.body ?? null; }
  }
  if (body === null) return null;
  const rb = new Robots(body);
  robotsMem.set(store, { at: Date.now(), r: rb });
  return rb;
}

async function scanQueue(store: string, n: number) {
  const spec = STORES[store];
  const robots = await robotsFor(store);
  if (!robots) { await status(store, false, "⚠️ مش قادرين نقرا robots.txt — مستنيين"); return { skipped: "no robots" }; }
  const targets: { id: number; url: string; kind: string; label: string | null; depth: number; fingerprint: string | null }[] =
    await rest("POST", "rpc/claim_targets", { p_store: store, p_n: n }, "return=representation");
  if (!targets.length) return { skipped: "queue empty" };
  const t0 = Date.now();
  const results = await Promise.all(targets.map(async (t) => {
    const s0 = Date.now();
    if (!robots.ok(t.url)) {
      await rest("POST", "rpc/finish_target", { p_id: t.id, p_status: "robots", p_items: 0, p_deals: 0, p_new: 0, p_fingerprint: null, p_ms: 0 });
      return { t, error: "robots" };
    }
    try {
      // Town Team collections: read the store's product JSON (exact prices); other pages: HTML
      const jsonUrl = store === "townteam" ? townTeamJsonUrl(t.url) : null;
      const [code, html] = await fetchPage(jsonUrl && robots.ok(jsonUrl) ? jsonUrl : t.url);
      if ([403, 429, 503].includes(code)) throw new Blocked(`HTTP ${code}`);
      const [parsed, cards] = jsonUrl && html.trimStart().startsWith("{") ? parseTownTeamJson(html) : spec.parse(html);
      const noDeal = store === "amazon" ? [] : [...lastNoDeal];   // read right after parsing (no await in between)
      const items: any[] = [...parsed];
      if (store === "amazon") {
        // only deals with a real old price: cards without one are checked on the product page (one per run)
        await verifyAmazonCandidate(items);
      }
      const fp = fingerprint(parsed);
      const res = items.length ? await ingest(items) : { accepted: 0, events: [], notifications: [] };
      if (noDeal.length) await rest("POST", "rpc/deactivate_seen_without_deal", { p_ids: noDeal.slice(0, 500) });
      const pushed = await dealPushes(res.events, STORE_NAMES);
      await personalPushes(res.notifications);
      // discovery: category links (only when the page changed) + next page
      const found = [];
      if (fp !== t.fingerprint) found.push(...categoryLinks(store, html, t.depth, robots));
      const np = nextPage(store, t.url, cards, res.events.length, t.depth);
      if (np && robots.ok(np.url)) found.push(np);
      const added = found.length ? await rest("POST", "rpc/add_targets", { p_store: store, p_rows: found }, "return=representation") : 0;
      await rest("POST", "rpc/finish_target", { p_id: t.id, p_status: "ok", p_items: cards, p_deals: res.accepted, p_new: res.events.length, p_fingerprint: fp, p_ms: Date.now() - s0 });
      return { t, code, cards, accepted: res.accepted, events: res.events.length, added, pushed, ms: Date.now() - s0 };
    } catch (e) {
      const blocked = e instanceof Blocked;
      await rest("POST", "rpc/finish_target", { p_id: t.id, p_status: blocked ? "blocked" : "error", p_items: null, p_deals: null, p_new: null, p_fingerprint: null, p_ms: Date.now() - s0 });
      return { t, error: String(e).slice(0, 160), blocked };
    }
  }));
  const ok = results.filter((r) => !("error" in r)) as any[];
  const cards = ok.reduce((a, r) => a + r.cards, 0), acc = ok.reduce((a, r) => a + r.accepted, 0), ev = ok.reduce((a, r) => a + r.events, 0);
  const added = ok.reduce((a, r) => a + (r.added ?? 0), 0);
  const label = results.map((r) => r.t.label ?? new URL(r.t.url).pathname).join(" + ").slice(0, 80);
  await status(store, ok.length > 0, ok.length
    ? `✓ ${acc} عرض من ${cards} منتج (${label}) · جديد/نزل: ${ev} · أقسام/صفحات جديدة: ${added} · ${Date.now() - t0}ms`
    : `⚠️ ${(results[0] as any).error}`, acc);
  if (store === "amazon" && Math.floor(Date.now() / 30_000) % 180 === 0) await rest("POST", "rpc/expire_stale_deals", { p_hours: 36 });
  return { store, code: (results[0] as any).code, targets: results.map((r: any) => ({ url: r.t.url, code: r.code, cards: r.cards, accepted: r.accepted, events: r.events, added: r.added, ms: r.ms, error: r.error })),
    cards, accepted: acc, events: ev, added, fetch_ms: Date.now() - t0 };
}

async function runSearch(id: number) {
  const r = (await select(`search_requests?select=id,query,stores,status&id=eq.${id}`))[0];
  if (!r || r.status !== "pending") return { skipped: true };
  await rest("PATCH", `search_requests?id=eq.${id}`, { status: "running" });
  const cfg = await config();
  // stores without a permitted search page (noon: robots.txt) are skipped; their categories are scanned on schedule
  const stores = (r.stores?.length ? r.stores : cfg.scrape_stores ?? Object.keys(STORES)).filter((s: string) => STORES[s]?.search);
  let total = 0;
  await Promise.all(stores.map(async (s: string) => {
    try {
      const [code, html] = await fetchPage(STORES[s].search!(r.query));
      if (code !== 200) return;
      const items: any[] = [...STORES[s].parse(html)[0]];
      const res = await ingest(items);
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

// ───────── one-time store probe (Phase 0): ONE plain request per call, allowlisted hosts only, no retries,
// no cookies, no challenge solving. Returns what the page offers, not the page.
const PROBE_HOSTS = ["www.amazon.eg", "www.noon.com", "www.jumia.com.eg", "www.defacto.com", "defacto.com", "www.lcwaikiki.eg",
  "lcwaikiki.eg", "townteam.com", "www.townteam.com", "www.townteam.com.eg", "townteam.com.eg", "www.raneen.com", "raneen.com", "btech.com", "www.btech.com", "eg.pricena.com", "yaoota.com", "t.me"];
async function probe(url: string) {
  const u = new URL(url);
  if (!PROBE_HOSTS.includes(u.hostname)) return { error: "host not allowed" };
  const t0 = Date.now();
  let r: Response;
  try {
    r = await fetch(url, { redirect: "follow", signal: AbortSignal.timeout(20_000), headers: {
      "User-Agent": UAS[0], "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "Accept-Language": "en-US,en;q=0.8,ar;q=0.7" } });
  } catch (e) { return { url, error: String(e).slice(0, 200), ms: Date.now() - t0 }; }
  const html = await r.text();
  const ms = Date.now() - t0;
  const count = (re: RegExp) => (html.match(re) ?? []).length;
  const base = { url, final: r.url, status: r.status, bytes: html.length, ms, contentType: r.headers.get("content-type") ?? "",
    title: (html.match(/<title[^>]*>([^<]{0,140})/i)?.[1] ?? "").trim(),
    challenge: /Just a moment|cf-chl|challenges\.cloudflare|captcha|Attention Required|Access Denied|validateCaptcha|px-captcha|perimeterx/i.test(html.slice(0, 20000)) };
  if (u.pathname.endsWith("robots.txt")) return { ...base, robots: html.slice(0, 60000) };
  if (u.pathname.endsWith(".xml") || html.trimStart().startsWith("<?xml")) {
    const locs = [...html.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]);
    return { ...base, sitemapEntries: locs.length, sitemapSample: locs.slice(0, 15) };
  }
  const links = [...new Set([...html.matchAll(/href="(\/[^"#?]{2,120})"/g)].map((m) => m[1]))];
  let parsed: unknown = null;   // what our existing parsers would extract from this page (no extra requests)
  try {
    const store = u.hostname.includes("noon") ? "noon" : u.hostname.includes("amazon") ? "amazon" : null;
    const items = store ? STORES[store].parse(html)[0] : generic(html, "probe", `${u.protocol}//${u.hostname}/`);
    const deals = items.filter((i) => (i.original_price ?? 0) > i.current_price && ((i.original_price ?? 0) - i.current_price) / (i.original_price ?? 1) >= 0.1);
    parsed = { items: items.length, deals10: deals.length, sample: deals.slice(0, 3).map((i) => `${i.title.slice(0, 50)} — ${i.current_price}/${i.original_price}`) };
  } catch (e) { parsed = { error: String(e).slice(0, 120) }; }
  const dropdown = [...html.matchAll(/<option[^>]*value="search-alias=([a-z0-9-]+)"/gi)].map((m) => m[1]);
  return { ...base,
    markers: { amazonCards: count(/data-component-type="s-search-result"/g), amazonStrike: count(/a-text-price/g),
      noonProductLinks: count(/href="[^"]*\/p\//g), noonSalePrice: count(/sale_price/g), jumiaCards: count(/<article class="prd/g),
      jsonLdProduct: count(/"@type"\s*:\s*"Product"/g), strikeTags: count(/<(del|s)\b|line-through|class="[^"]*(old|was|strike)[^"]*"/gi),
      priceWords: count(/(EGP|ج\.م|جنيه)/g), nextData: html.includes("__NEXT_DATA__") || html.includes("self.__next_f") },
    parsed, amazonDepartments: dropdown.length, amazonDepartmentSample: dropdown.slice(0, 60),
    linkCount: links.length, linkSample: links.filter((l) => !/\.(js|css|png|jpg|svg|ico|woff)/.test(l)).slice(0, 150) };
}

// ───────── owner's public Telegram channel → "عروض مجنونة" (latest page + one older page per run until the start)
const TG_CHANNELS = new Set(["deals_me"]);
let tgRobots: { at: number; ok: (u: string) => boolean } | null = null;
async function runTelegram(channel: string) {
  if (!TG_CHANNELS.has(channel)) return { error: "channel not allowed" };
  if (!tgRobots || Date.now() - tgRobots.at > 86400_000) {
    const r = await fetch("https://t.me/robots.txt", { signal: AbortSignal.timeout(15_000), headers: { "User-Agent": UAS[1] } });
    const body = r.ok ? await r.text() : "";
    if (!r.ok && !(r.status >= 400 && r.status < 500)) return { error: `robots ${r.status}` };   // RFC 9309: 4xx = no rules
    const rb = new Robots(body);
    tgRobots = { at: Date.now(), ok: (u) => rb.ok(u) };
  }
  const page = async (before?: number) => {
    const url = `https://t.me/s/${channel}${before ? `?before=${before}` : ""}`;
    if (!tgRobots!.ok(url)) return [];
    const r = await fetch(url, { signal: AbortSignal.timeout(20_000), headers: { "User-Agent": UAS[1], "Accept-Language": "ar,en;q=0.8" } });
    if (!r.ok) throw new Error(`t.me HTTP ${r.status}`);
    return parseTelegram(await r.text(), channel);
  };
  const save = async (posts: any[]) => posts.length
    ? rest("POST", "tg_posts?on_conflict=channel,post_id", posts.map((p) => ({ channel, ...p })), "return=minimal,resolution=merge-duplicates") : null;
  const latest = await page();
  await save(latest);
  const oldest = (await select(`tg_posts?select=post_id&channel=eq.${channel}&order=post_id.asc&limit=1`))[0]?.post_id;
  let older: any[] = [];
  if (oldest && oldest > 1) { older = await page(oldest); await save(older.filter((p) => p.post_id < oldest)); }
  return { latest: latest.length, older: older.length, oldest, withPrice: latest.filter((p) => p.price).length };
}

// ───────── "يستاهل ولا لأ؟": live prices of the same kind of product (stores whose search page robots.txt allows)
async function runCompare(q: string) {
  if (q.trim().length < 3) return { offers: [] };
  const offers: { store: string; title: string; url: string; price: number; image_url: string | null }[] = [];
  const robots = await robotsFor("amazon");
  const url = STORES.amazon.search!(q);
  if (robots?.ok(url)) {
    try {
      const [code, html] = await fetchPage(url);
      if (code === 200) {
        const [deals] = STORES.amazon.parse(html);
        for (const d of deals) offers.push({ store: "amazon", title: d.title, url: d.url, price: d.current_price, image_url: d.image_url });
        for (const c of amazonCandidates) offers.push({ store: "amazon", title: c.title, url: `https://www.amazon.eg/dp/${c.asin}`, price: c.current, image_url: c.image_url });
      }
    } catch (e) { console.warn("compare", String(e)); }
  }
  return { offers: offers.slice(0, 30) };
}

// ───────── AI audit: category for the side menu + hide deals with an inflated "before" price (free Gemini tier)
async function runAudit(n: number) {
  const rows = await select(`deals?select=id,title,store_id,current_price,original_price&active=eq.true&audit_status=eq.unchecked&order=deal_pct.desc.nullslast&limit=${n}`);
  if (!rows.length) return { audited: 0 };
  const res = await audit(rows.map((r: any) => ({ id: r.id, title: r.title ?? "", store: r.store_id,
    current_price: Number(r.current_price), original_price: r.original_price == null ? null : Number(r.original_price) })));
  if (!res) return { audited: 0, error: "gemini unavailable" };
  return { audited: res.length, result: await rest("POST", "rpc/apply_audit", { p_rows: res }, "return=representation") };
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
    if (body.job === "order") out = await runOrder(String(body.id));
    else if (body.job === "probe") out = await probe(String(body.url));
    else if (body.job === "telegram") out = await runTelegram(String(body.channel ?? "deals_me"));
    else if (body.job === "webcheck") out = { offers: await webOffers(String(body.q ?? ""), Number(body.price) || 0), raw: WS.lastRaw, aiErrors };
    else if (body.job === "compare") out = await runCompare(String(body.q ?? "").slice(0, 120));
    else if (body.job === "audit") out = await runAudit(Math.min(60, Number(body.n) || 30));
    else if (body.job === "debug_url") {   // CI diagnostics: product data embedded in an allowlisted page (one request)
      const u = new URL(String(body.url));
      if (!PROBE_HOSTS.includes(u.hostname)) out = { error: "host not allowed" };
      else {
        const [code, html] = await fetchPage(u.toString());
        const nd = html.match(/<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)<\/script>/)?.[1] ?? "";
        const i = nd.search(/"(price|final_price|special_price|regular_price)"/);
        out = { code, length: html.length, nextData: nd.length, sample: i >= 0 ? nd.slice(Math.max(0, i - 1500), i + 1500) : nd.slice(0, 2500) };
      }
    }   // pushes: needed even when the VM worker runs
    else if (await vmAlive()) out = { skipped: "vm worker active" };
    else if (body.job === "scan") {
      const st = String(body.store);
      const mode = (await config()).scanner_mode ?? "queue";
      out = mode === "queue" && QUEUE_STORES.has(st) ? await scanQueue(st, st === "noon" ? 2 : 1) : await scanStore(st);
      if (body.store === "amazon") await status("edge", true, `شغال ✓ ${new Date().toISOString().slice(11, 19)} UTC · Supabase Edge كل 20 ث · push ${pushEnabled() ? "on" : "off"}`);
    } else if (body.job === "debug") {   // CI diagnostics only (secret-protected): first product card of a store page
      const spec = STORES[String(body.store)];
      const [code, html] = await fetchPage(spec.deals[0] ?? spec.search?.("headphones") ?? "");
      const marks = [...html.matchAll(/data-component-type="s-search-result"|<article class="prd|href="[^"]*\/p\//g)].map((m) => m.index!);
      if (String(body.store) === "noon") {   // noon product data: scalar fields of the first hits (prices, offers)
        const starts = [...html.matchAll(/[{,]\s*"?offer_code"?\s*:/g)].map((m) => m.index!);
        const hits = starts.slice(0, 4).map((st, i) => {
          const chunk = html.slice(st, Math.min(starts[i + 1] ?? html.length, st + 8000));
          return [...chunk.matchAll(/[{,]\s*"?([a-z_A-Z]+)"?\s*:\s*("(?:[^"\\]|\\.){0,80}"|-?[0-9.]+|!0|!1|null)/g)]
            .filter((m) => !/image|url|key|spec|name|slug/i.test(m[1])).map((m) => `${m[1]}=${m[2]}`).join(" ");
        });
        out = { code, length: html.length, sample: hits.join("\n=====\n") };
        return Response.json(out);
      }
      const card = marks.length > 2 ? html.slice(marks[1], marks[2]) : html.slice(0, 60000);
      const bits = [...card.matchAll(/price|offscreen|strike|EGP|جنيه|%/gi)].slice(0, 25).map((m) => card.slice(Math.max(0, m.index! - 150), m.index! + 250).replace(/\s+/g, " "));
      out = { code, length: html.length, cardLength: card.length, sample: bits.join("\n---\n") || card.slice(-3000) };
    } else if (body.job === "search") out = await runSearch(Number(body.id));
    else if (body.job === "shein") out = await runShein(Number(body.id));
    else out = { error: "unknown job" };
    return Response.json(out);
  } catch (e) {
    console.error(e);
    return Response.json({ error: String(e) }, { status: 500 });
  }
});
