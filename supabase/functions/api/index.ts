// App API (signed-in users only; Supabase verifies the JWT before this runs).
//   POST {action:"search", q, stores?, min_pct?, min_price?, max_price?, sort?, page?}
//   POST {action:"verify", deal_id}            → "يستاهل ولا لأ؟"
//   POST {action:"chat", messages:[{role,text}]} → shopping assistant (Gemini free tier, with our real deals)
import { gemini, geminiConfigured, type Turn } from "./gemini.ts";
import { dictTranslate, hasArabic, maxPriceOf, normalize, similarity, titleTokens, words } from "./text.ts";
import { Robots } from "../_shared/robots.ts";

const URL_ = Deno.env.get("SUPABASE_URL")!.replace(/\/$/, "");
const KEY = Deno.env.get("SERVICE_KEY") || Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const COLS = "id,store_id,title,url,image_url,category,original_price,current_price,discount_pct,deal_pct,min_discount_pct,best_price,rating,rating_count,active,deal_since,last_checked,updated_at";
const STORE_AR: Record<string, string> = { amazon: "أمازون", noon: "نون", jumia: "جوميا", townteam: "تاون تيم" };
const CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, apikey, content-type" };

async function rest(method: string, path: string, body?: unknown, prefer = "return=representation"): Promise<any> {
  const r = await fetch(`${URL_}/rest/v1/${path}`, { method, body: body === undefined ? undefined : JSON.stringify(body),
    headers: { apikey: KEY, Authorization: `Bearer ${KEY}`, "Content-Type": "application/json", Prefer: prefer } });
  if (!r.ok) throw new Error(`${method} ${path.split("?")[0]} → ${r.status}: ${(await r.text()).slice(0, 200)}`);
  const t = await r.text();
  return t ? JSON.parse(t) : null;
}
const enc = encodeURIComponent;
const ilikeWord = (w: string) => `title.ilike.*${w.replace(/[(),*]/g, " ").trim()}*`;

function userId(req: Request): string | null {
  try {
    const tok = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "");
    const p = JSON.parse(atob(tok.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
    return p.role === "authenticated" ? p.sub : null;
  } catch { return null; }
}

// ───────── query translation (dictionary → cache → Gemini)
async function englishOf(q: string): Promise<string | null> {
  const n = normalize(q);
  if (!hasArabic(n)) return null;
  const d = dictTranslate(n);
  if (d) return d;
  const hit = (await rest("GET", `query_translations?select=en&q=eq.${enc(n)}`))[0];
  if (hit) return hit.en || null;
  const g = await gemini("You translate Egyptian Arabic shopping searches into the short English product search a shopper would type on Amazon. Reply with JSON {\"en\": \"...\"} only. Keep brand names and model numbers.",
    [{ role: "user", text: q }], { json: true, maxTokens: 60, temperature: 0 });
  let en = "";
  try { en = String(JSON.parse(g?.text ?? "{}").en ?? "").toLowerCase().slice(0, 120); } catch { /* ignore */ }
  if (en) await rest("POST", "query_translations", [{ q: n, en }], "return=minimal,resolution=merge-duplicates").catch(() => {});
  return en || null;
}

// ───────── search
async function search(b: any, uid: string) {
  const q = String(b.q ?? "").trim().slice(0, 100);
  const en = q ? await englishOf(q) : null;
  const variants = [q, en].filter((v): v is string => !!v).map(words).filter((w) => w.length);
  const f: string[] = ["select=" + COLS, "active=eq.true"];
  if (variants.length) f.push(`or=(${variants.map((ws) => `and(${ws.map(ilikeWord).join(",")})`).join(",")})`);
  const stores = (b.stores ?? []).filter((s: string) => /^[a-z]+$/.test(s));
  if (stores.length) f.push(`store_id=in.(${stores.join(",")})`);
  if (Number(b.min_pct) > 0) f.push(`deal_pct=gte.${Number(b.min_pct)}`);
  if (Number(b.min_price) > 0) f.push(`current_price=gte.${Number(b.min_price)}`);
  if (Number(b.max_price) > 0) f.push(`current_price=lte.${Number(b.max_price)}`);
  const order = { price_asc: "current_price.asc", price_desc: "current_price.desc", newest: "deal_since.desc" }[String(b.sort)] ?? "deal_pct.desc.nullslast";
  const page = Math.max(1, Number(b.page) || 1), size = 30;
  f.push(`order=${order}`, `limit=${size + 1}`, `offset=${(page - 1) * size}`);
  const rows = await rest("GET", `deals?${f.join("&")}`);
  // live search in the stores (server side) for new queries; results arrive in the DB within seconds
  let live = false;
  if (!b.no_live && q.length >= 2 && page === 1 && rows.length < 10) {
    const lq = en || q;
    await rest("POST", "search_requests", [{ user_id: uid, query: lq.slice(0, 80), ...(stores.length ? { stores } : {}) }], "return=minimal")
      .then(() => { live = true; }).catch(() => {});
  }
  return { items: rows.slice(0, size), has_more: rows.length > size, english: en, live };
}

// ───────── "يستاهل ولا لأ؟"
async function verify(dealId: string) {
  const d = (await rest("GET", `deals?select=${COLS}&id=eq.${enc(dealId)}`))[0];
  if (!d) return { error: "العرض ده مبقاش موجود" };
  const price = Number(d.current_price);
  const cached = (await rest("GET", `deal_verdicts?select=result,created_at&deal_id=eq.${enc(dealId)}&price=eq.${price}`))[0];
  if (cached && Date.now() - Date.parse(cached.created_at) < 12 * 3600_000) return cached.result;

  const hist: { current_price: string; original_price: string | null; checked_at: string }[] =
    await rest("GET", `price_history?select=current_price,original_price,checked_at&deal_id=eq.${enc(dealId)}&order=checked_at.asc&limit=500`);
  const prices = hist.map((h) => Number(h.current_price));
  const firstSeen = hist[0]?.checked_at ?? d.deal_since;
  const days = Math.max(0, (Date.now() - Date.parse(firstSeen)) / 86400_000);
  const lowest = prices.length ? Math.min(...prices, price) : price;
  const highest = prices.length ? Math.max(...prices, price) : price;
  const orig = d.original_price ? Number(d.original_price) : null;
  const origEverCharged = orig ? prices.some((p) => p >= orig * 0.97) : false;

  // same product in other stores (our data): match distinctive title tokens
  const toks = titleTokens(d.title).slice(0, 3);
  let cheaper: any = null;
  if (toks.length >= 2) {
    const others = await rest("GET", `deals?select=id,store_id,title,url,current_price,image_url&active=eq.true&store_id=neq.${d.store_id}&and=(${toks.map(ilikeWord).join(",")})&order=current_price.asc&limit=10`);
    cheaper = others.find((o: any) => similarity(o.title, d.title) >= 0.7 && Number(o.current_price) < price * 0.97) ?? null;
  }

  const enough = hist.length >= 3 || days >= 3;
  let verdict: string, code: string, score: number | null = null;
  if (cheaper) { code = "cheaper"; verdict = "مايستاهلش — فيه أرخص"; }
  else if (!enough) { code = "insufficient"; verdict = "بيانات غير كافية للحكم"; }
  else if (orig && !origEverCharged && days >= 7 && highest <= price * 1.03) { code = "fake"; verdict = "الخصم مش موثوق"; }
  else if (price <= lowest * 1.01 && Number(d.deal_pct) >= 30) { code = "excellent"; verdict = "صفقة ممتازة"; }
  else if (price <= lowest * 1.05) { code = "good"; verdict = "سعر جيد"; }
  else { code = "normal"; verdict = "خصم عادي، مش أفضل وقت للشراء"; }
  if (enough && !cheaper) {
    const pos = highest > lowest ? (highest - price) / (highest - lowest) : 0.5;   // 1 = at the lowest we have seen
    score = Math.round(Math.min(10, Math.max(0, 3 + pos * 4 + Math.min(3, Number(d.deal_pct ?? 0) / 20) - (code === "fake" ? 4 : 0))));
  }
  const facts = {
    product: d.title.slice(0, 160), store: STORE_AR[d.store_id] ?? d.store_id, current_price: price, advertised_original: orig,
    discount_pct: Number(d.deal_pct ?? 0), observations: hist.length, days_tracked: Math.round(days * 10) / 10,
    lowest_seen: lowest, highest_seen: highest, original_ever_charged: orig ? origEverCharged : "no original price shown",
    coupon_price: d.best_price ? Number(d.best_price) : null,
    cheaper_elsewhere: cheaper ? { store: STORE_AR[cheaper.store_id] ?? cheaper.store_id, price: Number(cheaper.current_price) } : null,
    verdict,
  };
  const g = await gemini(
    "انت مساعد تسوق مصري أمين. اكتب 2 إلى 3 جمل قصيرة بالعامية المصرية تشرح الحكم على العرض باستخدام الأرقام المعطاة فقط. " +
    "ممنوع تخترع أسعار أو تواريخ أو معلومات مش موجودة. لو البيانات قليلة قول كده بوضوح.",
    [{ role: "user", text: JSON.stringify(facts) }], { maxTokens: 220, temperature: 0.3 });
  const fallback = [
    cheaper ? `نفس المنتج تقريباً على ${facts.cheaper_elsewhere!.store} بـ ${Math.round(cheaper.current_price)} ج.م بدل ${Math.round(price)}.` : null,
    enough ? `أقل سعر شفناه ${Math.round(lowest)} ج.م وأعلى سعر ${Math.round(highest)} ج.م خلال ${Math.max(1, Math.round(days))} يوم.` :
      `بدأنا نتابع السعر من ${Math.max(1, Math.round(days))} يوم بس، فلسه بدري نحكم.`,
    orig && enough && !origEverCharged ? "السعر قبل الخصم ماشفناهوش متباع بيه قبل كده." : null,
  ].filter(Boolean).join(" ");
  const result = { verdict, code, score, explanation: g?.text ?? fallback, ai: !!g, model: g?.model ?? null, facts,
    history: hist.slice(-60).map((h) => ({ at: h.checked_at, price: Number(h.current_price) })),
    cheaper: cheaper ? { id: cheaper.id, store: cheaper.store_id, title: cheaper.title, url: cheaper.url, price: Number(cheaper.current_price), image_url: cheaper.image_url } : null };
  await rest("POST", "deal_verdicts", [{ deal_id: dealId, price, result }], "return=minimal,resolution=merge-duplicates").catch(() => {});
  return result;
}

// ───────── assistant
const DAILY_LIMIT = 40;
async function chat(b: any, uid: string) {
  const msgs: { role: string; text: string }[] = (b.messages ?? []).slice(-10)
    .map((m: any) => ({ role: m.role === "assistant" ? "model" : "user", text: String(m.text ?? "").slice(0, 1500) })).filter((m: any) => m.text);
  const last = [...msgs].reverse().find((m) => m.role === "user")?.text ?? "";
  if (!last) return { reply: "اكتبلي محتاج إيه وأنا أساعدك.", products: [] };
  const n = await rest("POST", "rpc/bump_assistant_usage", { p_user: uid });
  if (Number(n) > DAILY_LIMIT) return { reply: `وصلت للحد اليومي (${DAILY_LIMIT} رسالة). تقدر تسألني تاني بكرة، ولحد ما تقدر تدور بنفسك من صفحة البحث.`, products: [], limited: true };

  // real offers from our database for what the user asked (always, even if the AI is busy)
  const maxPrice = maxPriceOf(last);
  const en = await englishOf(last);
  const queryWords = (en ? words(en) : words(last)).slice(0, 3);
  let products: any[] = [];
  if (queryWords.length) {
    const f = [`select=${COLS}`, "active=eq.true", `and=(${queryWords.map(ilikeWord).join(",")})`, "order=deal_pct.desc.nullslast", "limit=8"];
    if (maxPrice) f.push(`current_price=lte.${maxPrice}`);
    products = await rest("GET", `deals?${f.join("&")}`).catch(() => []);
    if (!products.length && queryWords.length > 1) {
      f[2] = `or=(${queryWords.map(ilikeWord).join(",")})`;
      products = await rest("GET", `deals?${f.join("&")}`).catch(() => []);
    }
  }
  const catalog = products.map((p, i) => ({ n: i + 1, title: p.title.slice(0, 120), store: STORE_AR[p.store_id] ?? p.store_id,
    price: Number(p.current_price), before: p.original_price ? Number(p.original_price) : null, discount: Number(p.deal_pct ?? 0),
    coupon_price: p.best_price ? Number(p.best_price) : null }));
  const system = [
    "انت \"مساعد عروض مصر\": مساعد تسوق ذكي بالعامية المصرية، ودود ومختصر ومفيد.",
    "بتساعد في: ترشيح منتجات حسب الميزانية والاستخدام، المقارنات، شرح المواصفات، المقاسات، وهل العرض يستاهل.",
    "عندك قايمة عروض حقيقية من قاعدة بياناتنا (أمازون ونون وتاون تيم). لما ترشح منتج منها اذكر رقمه بين قوسين زي [2] واسم المتجر وسعره كما هو بالظبط.",
    "ممنوع تخترع أسعار أو عروض أو روابط. لو القايمة فاضية أو مش مناسبة قول كده بوضوح، واقترح مواصفات يدور عليها أو كلمة بحث أدق.",
    "لما تقول معلومة عامة من معرفتك (مش من القايمة) وضّح إنها معلومة عامة ممكن تختلف.",
    "اسأل سؤال توضيحي واحد بس لو الطلب مش واضح فعلاً (زي الميزانية أو المقاس).",
    `القايمة: ${JSON.stringify(catalog)}`,
  ].join("\n");
  const turns: Turn[] = msgs.map((m) => ({ role: m.role as "user" | "model", text: m.text }));
  while (turns.length && turns[0].role !== "user") turns.shift();
  const g = await gemini(system, turns, { maxTokens: 700, temperature: 0.5 });
  const reply = g?.text ?? (products.length
    ? "المساعد الذكي مشغول دلوقتي، بس دي أقرب العروض الحقيقية لطلبك:"
    : "المساعد الذكي مشغول دلوقتي ومالقيتش عروض مطابقة في قاعدة بياناتنا. جرب كلمة بحث تانية من صفحة البحث.");
  return { reply, products, ai: !!g, model: g?.model ?? null };
}

// ───────── phone assist: a phone read one queued Jumia page and sends compact product data (validated here)
async function jumiaRobots(): Promise<Robots | null> {
  const row = (await rest("GET", "robots_cache?select=body,fetched_at&store=eq.jumia"))[0];
  if (row && Date.now() - Date.parse(row.fetched_at) < 86400_000) return new Robots(row.body);
  try {
    const r = await fetch("https://www.jumia.com.eg/robots.txt", { signal: AbortSignal.timeout(15_000) });
    if (!r.ok) return row ? new Robots(row.body) : null;
    const body = await r.text();
    await rest("POST", "robots_cache", [{ store: "jumia", body: body.slice(0, 200_000), fetched_at: new Date().toISOString() }], "return=minimal,resolution=merge-duplicates");
    return new Robots(body);
  } catch { return row ? new Robots(row.body) : null; }
}

async function phoneReport(b: any, uid: string) {
  const tid = Number(b.target_id);
  const claim = (await rest("GET", `phone_reports?select=id&user_id=eq.${uid}&target_id=eq.${tid}&status=eq.claimed&at=gt.${enc(new Date(Date.now() - 10 * 60_000).toISOString())}&order=at.desc&limit=1`))[0];
  if (!claim) return { error: "no claim" };
  const status = ["ok", "blocked", "error"].includes(b.status) ? b.status : "error";
  let saved = 0, events = 0, added = 0;
  const items: any[] = [];
  if (status === "ok") {
    for (const it of (b.items ?? []).slice(0, 80)) {
      const sku = String(it.sku ?? "").slice(0, 40), url = String(it.url ?? "");
      const price = Number(it.price), old = Number(it.old);
      if (!/^[A-Za-z0-9_-]{3,40}$/.test(sku) || !url.startsWith("https://www.jumia.com.eg/")) continue;
      if (!(price >= 5) || !(old > price) || (old - price) / old > 0.9 || old > 5_000_000) continue;
      const img = String(it.image_url ?? "");
      items.push({ id: `jumia:${sku}`, store: "jumia", title: String(it.title ?? "").slice(0, 300), url: url.split("#")[0],
        image_url: /^https:\/\/[a-z0-9.-]*jumia\.is\//.test(img) ? img : null, current_price: price, original_price: old });
    }
    if (items.length) {
      const r = await rest("POST", "rpc/ingest_deals", { p_items: items, p_min_pct: 10 });
      saved = r.accepted; events = (r.events ?? []).length;
    }
    const robots = await jumiaRobots();
    if (robots) {
      const links = [...new Set<string>((b.links ?? []).map(String))].slice(0, 60)
        .filter((l) => /^\/[a-z0-9-]+\/$/.test(l)).map((l) => `https://www.jumia.com.eg${l}`).filter((u) => robots.ok(u))
        .map((url) => ({ url, kind: "category", depth: 1, label: url.split("/").filter(Boolean).pop() }));
      if (links.length) added = Number(await rest("POST", "rpc/add_targets", { p_store: "jumia", p_rows: links })) || 0;
    }
  }
  await rest("POST", "rpc/finish_target", { p_id: tid, p_status: status, p_items: items.length, p_deals: saved, p_new: events, p_fingerprint: null, p_ms: Number(b.ms) || null });
  await rest("PATCH", `phone_reports?id=eq.${claim.id}`, { status, items: items.length, saved }, "return=minimal");
  await rest("POST", "scraper_status", [{ store: "jumia", ok: status === "ok", deals_seen: saved, last_run: new Date().toISOString(),
    message: status === "ok" ? `✓ (من موبايل) ${saved} عرض من ${items.length} · جديد/نزل: ${events} · صفحات جديدة: ${added}` : `⚠️ (من موبايل) ${status}` }],
    "return=minimal,resolution=merge-duplicates");
  return { saved, events, added };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  const uid = userId(req);
  if (!uid) return Response.json({ error: "سجل دخولك الأول" }, { status: 401, headers: CORS });
  try {
    const b = await req.json().catch(() => ({}));
    let out: unknown;
    if (b.action === "search") out = await search(b, uid);
    else if (b.action === "verify") out = await verify(String(b.deal_id ?? ""));
    else if (b.action === "chat") out = await chat(b, uid);
    else if (b.action === "status") out = { ai: geminiConfigured() };
    else if (b.action === "phone_report") out = await phoneReport(b, uid);
    else out = { error: "unknown action" };
    return Response.json(out, { headers: CORS });
  } catch (e) {
    console.error(e);
    return Response.json({ error: "حصلت مشكلة، جرب تاني" , detail: String(e).slice(0, 200) }, { status: 500, headers: CORS });
  }
});
