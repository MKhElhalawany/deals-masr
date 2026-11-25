// App API (signed-in users only; Supabase verifies the JWT before this runs).
//   POST {action:"search", q, stores?, min_pct?, min_price?, max_price?, sort?, page?}
//   POST {action:"verify", deal_id}            → "يستاهل ولا لأ؟"
//   POST {action:"chat", messages:[{role,text}]} → shopping assistant (Gemini free tier, with our real deals)
import { gemini, geminiConfigured, type Turn } from "../_shared/gemini.ts";
import { dictTranslate, hasArabic, maxPriceOf, normalize, similarity, titleTokens, words } from "./text.ts";
import { Robots } from "../_shared/robots.ts";
import { audit } from "../_shared/audit.ts";
import { webOffers } from "../_shared/websearch.ts";

const URL_ = Deno.env.get("SUPABASE_URL")!.replace(/\/$/, "");
const KEY = Deno.env.get("SERVICE_KEY") || Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const COLS = "id,store_id,title,url,image_url,category,audit_status,audit_note,typical_low,typical_high,brand,seller,original_price,current_price,discount_pct,deal_pct,min_discount_pct,best_price,rating,rating_count,active,deal_since,last_checked,updated_at";
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
/** live offers from the scan function (robots-checked store search), empty on any problem */
async function liveOffers(q: string): Promise<{ store: string; title: string; url: string; price: number; image_url: string | null }[]> {
  const secret = Deno.env.get("CRON_SECRET");
  if (!secret || q.length < 3) return [];
  try {
    const r = await fetch(`${URL_}/functions/v1/scan`, { method: "POST", signal: AbortSignal.timeout(12_000),
      headers: { "Content-Type": "application/json", "x-cron-secret": secret }, body: JSON.stringify({ job: "compare", q }) });
    return r.ok ? ((await r.json()).offers ?? []).filter((o: any) => Number(o.price) > 0) : [];
  } catch { return []; }
}

/** which candidates are the SAME product (model/size/pack) — AI when available, else strict title similarity */
async function sameProduct<T extends { title: string; price: number }>(title: string, price: number, pool: T[]): Promise<T[]> {
  if (!pool.length) return [];
  const g = await gemini("You match products. Given a target product and numbered candidates, return a JSON array of the numbers " +
    "of candidates that are the SAME product (same brand, model, size/capacity and pack count; color may differ). Reply [] if none.",
    [{ role: "user", text: JSON.stringify({ target: { title: title.slice(0, 200), price }, candidates: pool.map((p, i) => ({ n: i, title: p.title.slice(0, 160), price: p.price })) }) }],
    { json: true, maxTokens: 200, temperature: 0 });
  if (g) {
    try {
      const ids = JSON.parse(g.text);
      if (Array.isArray(ids)) return ids.map(Number).filter((i) => pool[i]).map((i) => pool[i])
        .filter((p) => p.price > price * 0.3 && p.price < price * 3);   // guard against accessories / bundles
    } catch { /* fall through */ }
  }
  return pool.filter((p) => similarity(p.title, title) >= 0.75 && p.price > price * 0.4 && p.price < price * 2.5);
}

/** Photo → the words SHEIN's search understands (free AI vision), so a photo search works inside the app. */
async function imageQuery(b: any) {
  const data = String(b.image ?? "");
  if (data.length < 100 || data.length > 1_500_000 || !/^[A-Za-z0-9+/=]+$/.test(data)) return { error: "الصورة مش واضحة" };
  const g = await gemini("You turn a product photo into a short shopping search query for SHEIN (English, 3-7 words: " +
    "colour + material/pattern + product type + key details, e.g. 'burgundy chiffon long sleeve maxi dress'). " +
    'Reply JSON {"query": string, "arabic": short Arabic name of the product}.',
    [{ role: "user", text: "Product photo:", image: { mime: b.mime === "image/png" ? "image/png" : "image/jpeg", data } }],
    { json: true, maxTokens: 120, temperature: 0 });
  if (!g) return { error: "البحث بالصورة مشغول دلوقتي، جرب كمان شوية" };
  try { const o = JSON.parse(g.text); if (o?.query) return { query: String(o.query).slice(0, 80), arabic: String(o.arabic ?? "").slice(0, 60) }; } catch { /* below */ }
  return { error: "معرفناش نحدد المنتج من الصورة، جرب صورة أوضح" };
}

async function verify(dealId: string) {
  const d = (await rest("GET", `deals?select=${COLS}&id=eq.${enc(dealId)}`))[0];
  if (!d) return { error: "العرض ده مبقاش موجود" };
  const price = Number(d.current_price);
  const cached = (await rest("GET", `deal_verdicts?select=result,created_at&deal_id=eq.${enc(dealId)}&price=eq.${price}`))[0];
  if (cached && cached.result?.code !== "insufficient" && Date.now() - Date.parse(cached.created_at) < 12 * 3600_000) return cached.result;

  const hist: { current_price: string; original_price: string | null; checked_at: string }[] =
    await rest("GET", `price_history?select=current_price,original_price,checked_at&deal_id=eq.${enc(dealId)}&order=checked_at.asc&limit=500`);
  const prices = hist.map((h) => Number(h.current_price));
  const firstSeen = hist[0]?.checked_at ?? d.deal_since;
  const days = Math.max(0, (Date.now() - Date.parse(firstSeen)) / 86400_000);
  const lowest = prices.length ? Math.min(...prices, price) : price;
  const highest = prices.length ? Math.max(...prices, price) : price;
  const orig = d.original_price ? Number(d.original_price) : null;
  const origEverCharged = orig ? prices.some((p) => p >= orig * 0.97) : false;

  // 1) candidates: our whole database (every store, active or recent) + live store search (robots-allowed)
  const toks = titleTokens(d.title).slice(0, 3);
  let pool: { id?: string; store: string; title: string; url: string; price: number; image_url: string | null }[] = [];
  if (toks.length >= 1) {
    const rows = await rest("GET", `deals?select=id,store_id,title,url,current_price,image_url,last_checked&id=neq.${enc(dealId)}&last_checked=gt.${enc(new Date(Date.now() - 14 * 86400_000).toISOString())}&and=(${toks.slice(0, 2).map(ilikeWord).join(",")})&order=current_price.asc&limit=25`).catch(() => []);
    pool = rows.map((o: any) => ({ id: o.id, store: o.store_id, title: o.title, url: o.url, price: Number(o.current_price), image_url: o.image_url }));
  }
  const [live, web] = await Promise.all([liveOffers(words(d.title).slice(0, 6).join(" ")), webOffers(d.title.slice(0, 150), price)]);
  for (const o of web) if (!pool.some((p) => p.url === o.url) && o.url !== d.url) pool.push(o);
  for (const o of live) if (!pool.some((p) => p.url === o.url) && o.url !== d.url) pool.push(o);
  const matches = await sameProduct(d.title, price, pool.slice(0, 40));
  const cheapest = matches.length ? matches.reduce((a, b) => (b.price < a.price ? b : a)) : null;
  const cheaper: any = cheapest && cheapest.price < price * 0.97
    ? { id: cheapest.id ?? null, store_id: cheapest.store, title: cheapest.title, url: cheapest.url, current_price: cheapest.price, image_url: cheapest.image_url } : null;

  // 2) the AI's estimate of the normal Egyptian price (from the audit, or now)
  let tLow = d.typical_low ? Number(d.typical_low) : null, tHigh = d.typical_high ? Number(d.typical_high) : null;
  let fakeNote: string | null = d.audit_status === "fake" ? d.audit_note : null;
  if (!tHigh && d.audit_status !== "fake") {
    const a = await audit([{ id: d.id, title: d.title, store: d.store_id, current_price: price, original_price: d.original_price ? Number(d.original_price) : null }]).catch(() => null);
    if (a?.[0]) {
      tLow = a[0].typical_low; tHigh = a[0].typical_high;
      if (a[0].status === "fake") fakeNote = a[0].note;
      await rest("POST", "rpc/apply_audit", { p_rows: a }).catch(() => {});
    }
  }

  const enough = hist.length >= 3 || days >= 3;
  let verdict: string, code: string, score: number | null = null;
  if (cheaper) { code = "cheaper"; verdict = "مايستاهلش — فيه أرخص"; }
  else if (fakeNote || (orig && enough && !origEverCharged && days >= 7 && highest <= price * 1.03)) { code = "fake"; verdict = "الخصم مش موثوق"; }
  else if (matches.length && price <= cheapest!.price * 1.01) { code = Number(d.deal_pct) >= 30 ? "excellent" : "good"; verdict = "يستاهل ✓ — ده أرخص سعر لقيناه"; }
  else if (tLow && tHigh && price <= tLow) { code = "excellent"; verdict = "يستاهل ✓ — أقل من سعره المعتاد"; }
  else if (tHigh && price <= tHigh) { code = "good"; verdict = "يستاهل — سعر كويس"; }
  else if (tHigh && price > tHigh * 1.15) { code = "normal"; verdict = "غالي شوية عن سعره المعتاد"; }
  else if (enough && price <= lowest * 1.01 && Number(d.deal_pct) >= 30) { code = "excellent"; verdict = "صفقة ممتازة"; }
  else if (enough && price <= lowest * 1.05) { code = "good"; verdict = "سعر جيد"; }
  else if (enough) { code = "normal"; verdict = "خصم عادي، مش أفضل وقت للشراء"; }
  else { code = "good"; verdict = "سعر معقول"; }
  {
    const pos = highest > lowest ? (highest - price) / (highest - lowest) : 0.5;
    const vsTypical = tHigh ? Math.max(-2, Math.min(3, (tHigh - price) / tHigh * 6)) : 0;
    score = code === "cheaper" ? 3 : code === "fake" ? 2 :
      Math.round(Math.min(10, Math.max(0, 4 + pos * 2 + vsTypical + Math.min(2, Number(d.deal_pct ?? 0) / 25))));
  }
  const facts = {
    product: d.title.slice(0, 160), store: STORE_AR[d.store_id] ?? d.store_id, current_price: price, advertised_original: orig,
    discount_pct: Number(d.deal_pct ?? 0), observations: hist.length, days_tracked: Math.round(days * 10) / 10,
    lowest_seen: lowest, highest_seen: highest, original_ever_charged: orig ? origEverCharged : "no original price shown",
    coupon_price: d.best_price ? Number(d.best_price) : null,
    cheaper_elsewhere: cheaper ? { store: STORE_AR[cheaper.store_id] ?? cheaper.store_id, price: Number(cheaper.current_price) } : null,
    same_product_offers: matches.slice(0, 5).map((m) => ({ store: STORE_AR[m.store] ?? m.store, price: m.price })),
    typical_price_egypt: tHigh ? { low: tLow, high: tHigh, source: "تقدير الذكاء الاصطناعي" } : null,
    fake_reason: fakeNote, verdict,
  };
  const g = await gemini(
    "انت مساعد تسوق مصري أمين. اكتب 2 إلى 3 جمل قصيرة بالعامية المصرية تشرح الحكم على العرض باستخدام الأرقام المعطاة فقط. " +
    "ممنوع تخترع أسعار أو تواريخ أو معلومات مش موجودة. لو البيانات قليلة قول كده بوضوح.",
    [{ role: "user", text: JSON.stringify(facts) }], { maxTokens: 220, temperature: 0.3 });
  const fallback = [
    cheaper ? `نفس المنتج تقريباً على ${facts.cheaper_elsewhere!.store} بـ ${Math.round(cheaper.current_price)} ج.م بدل ${Math.round(price)}.` : null,
    !cheaper && matches.length ? `قارنّا بـ ${matches.length} عرض لنفس المنتج، وده أرخصهم.` : null,
    tHigh ? `سعره المعتاد في مصر تقريباً من ${Math.round(tLow ?? tHigh * 0.8)} لـ ${Math.round(tHigh)} ج.م.` : null,
    fakeNote ? `تنبيه: ${fakeNote}.` : null,
    enough ? `أقل سعر شفناه ${Math.round(lowest)} ج.م وأعلى سعر ${Math.round(highest)} ج.م خلال ${Math.max(1, Math.round(days))} يوم.` :
      null,
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

  // 1) what exactly is the user looking for right now (follow-ups like "هاتلي لينكها" refer to earlier messages)
  const intentAi = await gemini("From this shopping conversation, extract what the user wants to buy NOW. Reply JSON " +
    '{"query": exact product search query in English with brand/model/size if known, or null if no product is asked about, ' +
    '"max_price": number in EGP or null}.', msgs.slice(-6).map((m) => ({ role: m.role as "user" | "model", text: m.text })), { json: true, maxTokens: 120, temperature: 0 });
  let intent: { query: string | null; max_price: number | null } = { query: null, max_price: null };
  try { if (intentAi) intent = JSON.parse(intentAi.text); } catch { /* fallback below */ }
  const en = intent.query ? null : await englishOf(last);
  const searchQ = String(intent.query ?? en ?? last).slice(0, 100);
  const maxPrice = Number(intent.max_price) > 0 ? Number(intent.max_price) : maxPriceOf(last);
  const queryWords = words(searchQ).slice(0, 3);

  // 2) candidates: our deals + live store search + the whole web (Google search through the AI)
  let products: any[] = [];
  if (queryWords.length) {
    const f = [`select=${COLS}`, "active=eq.true", `and=(${queryWords.map(ilikeWord).join(",")})`, "order=deal_pct.desc.nullslast", "limit=12"];
    if (maxPrice) f.push(`current_price=lte.${maxPrice}`);
    products = await rest("GET", `deals?${f.join("&")}`).catch(() => []);
  }
  const [liveRaw, web] = intent.query || queryWords.length >= 2
    ? await Promise.all([liveOffers(searchQ), webOffers(searchQ, 0)]) : [[], []];
  let live = [...web, ...liveRaw].filter((o) => !maxPrice || o.price <= maxPrice);

  // 3) keep ONLY the exact thing asked for (no accessories, no other models)
  const cand = [...products.map((p) => ({ t: p.title as string, price: Number(p.current_price) })), ...live.map((o) => ({ t: o.title, price: o.price }))];
  if (cand.length && searchQ) {
    const keep = await gemini("Keep only candidates that ARE the product the user asked for (same product type, and same brand/model when " +
      "the request names one). Drop accessories, spare parts, cases, mounts and different products. Reply JSON array of kept numbers.",
      [{ role: "user", text: JSON.stringify({ request: searchQ, candidates: cand.map((c, i) => ({ n: i, title: c.t.slice(0, 140), price: c.price })) }) }],
      { json: true, maxTokens: 300, temperature: 0 });
    let ids: number[] | null = null;
    try { const a = keep ? JSON.parse(keep.text) : null; if (Array.isArray(a)) ids = a.map(Number); } catch { /* keep all */ }
    if (ids) {
      const set = new Set(ids), pLen = products.length;
      products = products.filter((_, i) => set.has(i));
      live = live.filter((_, i) => set.has(pLen + i));
    }
  }
  products = products.slice(0, 8);
  live = live.sort((a, b) => a.price - b.price).slice(0, 8);
  const catalog = products.map((p, i) => ({ n: i + 1, title: p.title.slice(0, 120), store: STORE_AR[p.store_id] ?? p.store_id,
    price: Number(p.current_price), before: p.original_price ? Number(p.original_price) : null, discount: Number(p.deal_pct ?? 0),
    coupon_price: p.best_price ? Number(p.best_price) : null }));
  const liveList = live.map((o, i) => ({ n: `L${i + 1}`, title: o.title.slice(0, 120), store: STORE_AR[o.store] ?? o.store, price: o.price }));
  const system = [
    "انت \"مساعد عروض مصر\": مساعد تسوق ذكي بالعامية المصرية، ودود ومختصر ومفيد.",
    "بتساعد في: ترشيح منتجات حسب الميزانية والاستخدام، المقارنات، شرح المواصفات، المقاسات، وهل العرض يستاهل.",
    "هدفك تفيد المستخدم بأرخص وأحسن مكان يشتري منه المنتج اللي طلبه بالظبط من أي موقع في مصر، مش تسوّق لعروض التطبيق.",
    "عندك قايمتين: عروض من قاعدة بياناتنا، ونتايج بحث في النت كله (جوجل والمتاجر). لما ترشح حاجة اذكر رقمها بين قوسين زي [2] أو [L1] واسم المتجر وسعره كما هو.",
    "اللينكات بتظهر للمستخدم ككروت تحت ردك يدوس عليها تفتح صفحة المتجر على طول — قوله كده. ممنوع تقول إن مفيش لينكات لو القايمة فيها حاجة.",
    "ممنوع تخترع أسعار أو عروض أو روابط. لو القايمة فاضية أو مش مناسبة قول كده بوضوح، واقترح مواصفات يدور عليها أو كلمة بحث أدق.",
    "لما تقول معلومة عامة من معرفتك (مش من القايمة) وضّح إنها معلومة عامة ممكن تختلف.",
    "اسأل سؤال توضيحي واحد بس لو الطلب مش واضح فعلاً (زي الميزانية أو المقاس).",
    `القايمة: ${JSON.stringify(catalog)}`,
    "قارن بين القايمتين وقول الأرخص بوضوح.",
    `نتايج النت (مرتبة من الأرخص): ${JSON.stringify(liveList)}`,
    ...(b.lang === "en" ? ["IMPORTANT: the user chose English. Reply in clear, friendly English."] : []),
    ...(typeof b.profile === "string" && b.profile ? [`اهتمامات المستخدم (اتعلمناها من استخدامه للتطبيق، استخدمها للترشيح): ${b.profile.slice(0, 300)}`] : []),
  ].join("\n");
  const turns: Turn[] = msgs.map((m) => ({ role: m.role as "user" | "model", text: m.text }));
  while (turns.length && turns[0].role !== "user") turns.shift();
  const g = await gemini(system, turns, { maxTokens: 700, temperature: 0.5 });
  const reply = g?.text ?? (products.length
    ? "المساعد الذكي مشغول دلوقتي، بس دي أقرب العروض الحقيقية لطلبك:"
    : "المساعد الذكي مشغول دلوقتي ومالقيتش عروض مطابقة في قاعدة بياناتنا. جرب كلمة بحث تانية من صفحة البحث.");
  return { reply, products, live: live.map((o, i) => ({ ...o, n: `L${i + 1}` })), ai: !!g, model: g?.model ?? null };
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
        image_url: /^https:\/\/[a-z0-9.-]*jumia\.is\//.test(img) ? img : null, current_price: price, original_price: old,
        brand: typeof it.brand === "string" ? it.brand.slice(0, 60) : null,
        rating: Number(it.rating) > 0 && Number(it.rating) <= 5 ? Number(it.rating) : null,
        rating_count: Number(it.rating_count) > 0 ? Math.floor(Number(it.rating_count)) : null });
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
  await rest("PATCH", `phone_reports?id=eq.${claim.id}`, { status, items: items.length, saved,
    note: `raw=${(b.items ?? []).length} ${String(b.note ?? "").replace(/[^\w=:;., -]/g, "").slice(0, 300)}` }, "return=minimal");
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
    else if (b.action === "image_query") out = await imageQuery(b);
    else if (b.action === "client_log") {   // in-app browser diagnostics (max 20 short lines per call)
      const rows = (Array.isArray(b.logs) ? b.logs : []).slice(0, 20).map((l: any) => ({ user_id: uid,
        kind: String(l.kind ?? "").slice(0, 30), message: String(l.message ?? "").slice(0, 500),
        url: String(l.url ?? "").replace(/[?#].*$/, "").slice(0, 200), app: String(b.app ?? "").slice(0, 20) }));
      if (rows.length) await rest("POST", "client_logs", rows, "return=minimal");
      out = { ok: rows.length };
    }
    else if (b.action === "phone_report") out = await phoneReport(b, uid);
    else out = { error: "unknown action" };
    return Response.json(out, { headers: CORS });
  } catch (e) {
    console.error(e);
    return Response.json({ error: "حصلت مشكلة، جرب تاني" , detail: String(e).slice(0, 200) }, { status: 500, headers: CORS });
  }
});
