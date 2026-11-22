// Store page parsers for the Supabase Edge scanner (port of server/worker/parsers.py, no browser).
// A deal needs both a crossed-out original price and a lower current price shown on the page.
import { parseHTML } from "npm:linkedom@0.18.5";

export type Item = {
  id: string; store: string; title: string; url: string; image_url: string | null;
  current_price: number; original_price: number; rating?: number | null; rating_count?: number | null;
};
export class Blocked extends Error {}

const AR = "٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹٬٫";
const EN = "01234567890123456789,.";
export function price(text: string | null | undefined): number | null {
  if (!text) return null;
  const t = [...text].map((c) => { const i = AR.indexOf(c); return i >= 0 ? EN[i] : c; }).join("");
  const m = t.match(/\d[\d,]*(?:\.\d+)?/);
  return m ? parseFloat(m[0].replace(/,/g, "")) : null;
}
const txt = (e: any) => (e?.textContent ?? "").replace(/\s+/g, " ").trim();
const abs = (href: string, base: string) => { try { return new URL(href, base).toString(); } catch { return ""; } };
const doc = (html: string) => (parseHTML(html) as any).document;

// ───────── Amazon.eg
export const amazonSearch = (q: string) => `https://www.amazon.eg/s?k=${encodeURIComponent(q).replace(/%20/g, "+")}&pct-off=10-&language=en_AE`;
export const AMAZON_DEALS = ["https://www.amazon.eg/s?i=specialty-aps&pct-off=30-&s=date-desc-rank&language=en_AE"];

/** Cards Amazon marks as deals but shows without the crossed-out price (verified later on the product page). */
export let amazonCandidates: { asin: string; title: string; image_url: string | null; current: number }[] = [];

/** Product page: verified current price + list ("was") price. */
export function parseAmazonProduct(html: string): { current: number; list: number } | null {
  if (html.includes("validateCaptcha")) throw new Blocked("amazon captcha");
  const d = doc(html);
  const cur = price(txt(d.querySelector("#corePriceDisplay_desktop_feature_div .priceToPay .a-offscreen, .priceToPay .a-offscreen, #corePrice_feature_div .a-offscreen")));
  const list = price(txt(d.querySelector("#corePriceDisplay_desktop_feature_div .basisPrice .a-offscreen, .basisPrice .a-text-price .a-offscreen, .a-price.a-text-price[data-a-strike=true] .a-offscreen")));
  return cur && list && list > cur ? { current: cur, list } : null;
}

export function parseAmazon(html: string): [Item[], number] {
  if (html.includes("validateCaptcha") || html.includes("Type the characters you see")) throw new Blocked("amazon captcha");
  const d = doc(html);
  const cards = [...d.querySelectorAll('[data-component-type="s-search-result"]')];
  const out: Item[] = [];
  amazonCandidates = [];
  for (const c of cards) {
    const asin = c.getAttribute("data-asin");
    const h2 = c.querySelector("h2");
    const title = ((h2?.getAttribute("aria-label")) || txt(h2)).trim();
    const cur = price(txt([...c.querySelectorAll(".a-price .a-offscreen")].find((e: any) => !e.parentElement?.classList.contains("a-text-price"))));
    const old = price(txt(c.querySelector(".a-price.a-text-price .a-offscreen"))) ?? price(txt(c.querySelector('[data-a-strike="true"] .a-offscreen')));
    if (asin && title && cur && !old && c.querySelector("[data-a-badge-type=deal], .a-badge")) {
      amazonCandidates.push({ asin, title, image_url: c.querySelector("img.s-image")?.getAttribute("src") ?? null, current: cur });
    }
    if (!asin || !title || !cur || !old) continue;
    const rating = price(txt(c.querySelector("span.a-icon-alt")));
    const cnt = [...c.querySelectorAll("a[href*=customerReviews] span, span.s-underline-text")].map((e: any) => price(txt(e))).find((x) => x);
    out.push({ id: `amazon:${asin}`, store: "amazon", title, url: `https://www.amazon.eg/dp/${asin}`,
      image_url: c.querySelector("img.s-image")?.getAttribute("src") ?? null, current_price: cur, original_price: old,
      rating: rating && rating <= 5 ? rating : null, rating_count: cnt ? Math.round(cnt) : null });
  }
  return [out.length || cards.length ? out : generic(html, "amazon", "https://www.amazon.eg/"), cards.length];
}

// ───────── Jumia Egypt
export const jumiaSearch = (q: string) => `https://www.jumia.com.eg/catalog/?q=${encodeURIComponent(q).replace(/%20/g, "+")}`;
export const JUMIA_DEALS = ["https://www.jumia.com.eg/flash-sales/"];

export function parseJumia(html: string): [Item[], number] {
  const d = doc(html);
  const title = (d.querySelector("title")?.textContent ?? "").toLowerCase();
  const cards = [...d.querySelectorAll("article.prd")];
  if (!cards.length && /access denied|attention required|just a moment/.test(title)) throw new Blocked("jumia blocked");
  const out: Item[] = [];
  for (const c of cards) {
    const a = c.querySelector("a.core") ?? c.querySelector("a[href]");
    if (!a) continue;
    const url = abs(a.getAttribute("href") ?? "", "https://www.jumia.com.eg/").split("#")[0];
    const name = txt(c.querySelector(".name")) || a.getAttribute("data-gtm-name") || a.getAttribute("data-ga4-item_name") || "";
    const cur = price(txt(c.querySelector(".prc"))), old = price(txt(c.querySelector(".old")));
    if (!name || !cur || !old) continue;
    const sku = a.getAttribute("data-gtm-id") || a.getAttribute("data-id") || a.getAttribute("data-ga4-item_id") ||
      url.match(/-(\d{5,})\.html/)?.[1] || url.split("/").pop()!.split(".")[0];
    const img = c.querySelector("img.img") ?? c.querySelector("img");
    const image = img ? (img.getAttribute("data-src") || img.getAttribute("src")) : null;
    const rating = price(txt(c.querySelector(".stars._s")));
    const rev = c.querySelector(".rev");
    const revOwn = rev ? [...rev.childNodes].filter((n: any) => n.nodeType === 3).map((n: any) => n.textContent).join("") : "";
    const cnt = price(revOwn);
    out.push({ id: `jumia:${sku}`, store: "jumia", title: name.trim(), url, image_url: image?.startsWith("http") ? image : null,
      current_price: cur, original_price: old, rating: rating && rating <= 5 ? rating : null, rating_count: cnt ? Math.round(cnt) : null });
  }
  const items = out.length ? out : generic(html, "jumia", "https://www.jumia.com.eg/");
  return [items, Math.max(cards.length, items.length)];
}

// ───────── noon Egypt (no browser here: server HTML + embedded JSON, best effort)
// noon's robots.txt disallows search pages (/*/search/, /*/search?) for all crawlers → no noon search URLs at all.
// Temporary coverage: a small set of category pages (allowed), page 1, 50 products each, until Phase 1.
// Temporary noon category set (robots.txt allows category pages). The homepage-linked paths tried on 2026-11-22
// (/mobiles/, /eg-home-appliances/, /home-kitchen/) are landing pages that yielded 0 deals → restored this set.
export const NOON_CATEGORIES: string[] = [
  "https://www.noon.com/egypt-en/electronics-and-mobiles/?limit=50",
  "https://www.noon.com/egypt-en/fashion/eg-fashion-men-cat/?limit=50",
  "https://www.noon.com/egypt-en/fashion/eg-fashion-women-cat/?limit=50",
  "https://www.noon.com/egypt-en/eg-fashion-footwear/?limit=50",
  "https://www.noon.com/egypt-en/beauty/?limit=50",
  "https://www.noon.com/egypt-en/sports-eg/?limit=50",
];
const noonId = (url: string) => {
  const seg = url.split("?")[0].replace(/\/$/, "").split("/");
  const i = seg.lastIndexOf("p");
  return i > 0 ? seg[i - 1] : defaultId(url);
};

export function parseNoon(html: string): [Item[], number] {
  if (html.slice(0, 5000).includes("Access Denied") && !html.includes("/p/")) throw new Blocked("noon blocked");
  const d = doc(html);
  const cards = d.querySelectorAll("a[href*='/p/']").length;
  const items = generic(html, "noon", "https://www.noon.com/", noonId, d);
  if (!items.length) items.push(...noonJson(html));
  return [items, Math.max(cards, items.length)];
}

/** Products embedded as JSON (Next.js data): objects with sku + name + price + sale_price. */
function noonJson(html: string): Item[] {
  const out = new Map<string, Item>();
  const re = /\{[^{}]*?"sku"\s*:\s*"([A-Z0-9]{6,})"[^{}]*?\}/g;
  for (const m of html.replace(/\\"/g, '"').matchAll(re)) {
    const o = m[0];
    const name = o.match(/"name"\s*:\s*"([^"]{4,300})"/)?.[1];
    const p = parseFloat(o.match(/"price"\s*:\s*"?([\d.]+)/)?.[1] ?? "");
    const sp = parseFloat(o.match(/"sale_price"\s*:\s*"?([\d.]+)/)?.[1] ?? "");
    if (!name || !(p > 0) || !(sp > 0) || sp >= p) continue;
    const slug = o.match(/"url"\s*:\s*"([^"]+)"/)?.[1] ?? "product";
    const img = o.match(/"image_key"\s*:\s*"([^"]+)"/)?.[1];
    out.set(m[1], { id: `noon:${m[1]}`, store: "noon", title: name, url: `https://www.noon.com/egypt-ar/${slug}/${m[1]}/p/`,
      image_url: img ? `https://f.nooncdn.com/p/${img}.jpg` : null, current_price: sp, original_price: p });
  }
  return [...out.values()];
}

// ───────── Town Team (Shopify): the store's own product JSON (/collections/<handle>/products.json) gives exact
// prices, "compare at" (original) prices, images and availability — no guessing from page layout.
export function townTeamJsonUrl(pageUrl: string): string | null {
  const u = new URL(pageUrl);
  const m = u.pathname.match(/^\/collections\/([a-z0-9\-]+)\/?$/i);
  if (!m) return null;
  const j = new URL(`https://townteam.com/collections/${m[1]}/products.json`);
  j.searchParams.set("limit", "250");
  j.searchParams.set("page", u.searchParams.get("page") ?? "1");
  return j.toString();
}

export function parseTownTeamJson(text: string): [Item[], number] {
  const data = JSON.parse(text);
  const products: any[] = data?.products ?? [];
  const out: Item[] = [];
  for (const p of products) {
    const vs = (p.variants ?? []).filter((v: any) => v.available !== false);
    let best: { cur: number; orig: number } | null = null;
    for (const v of vs) {
      const cur = parseFloat(v.price), orig = parseFloat(v.compare_at_price ?? "");
      if (!(cur > 0) || !(orig > cur)) continue;
      if (!best || cur < best.cur) best = { cur, orig };
    }
    if (!best || !p.handle || !p.title) continue;
    const img = p.images?.[0]?.src ?? null;
    out.push({ id: `townteam:${p.handle}`, store: "townteam", title: String(p.title), url: `https://townteam.com/products/${p.handle}`,
      image_url: img ? (img.startsWith("//") ? "https:" + img : img) : null, current_price: best.cur, original_price: best.orig });
  }
  return [out, products.length];
}

export function parseTownTeam(html: string): [Item[], number] {   // HTML pages (homepage): links only, prices come from JSON
  const d = doc(html);
  return [[], d.querySelectorAll("a[href*='/products/']").length];
}

// ───────── generic deal-card detector (crossed-out price + lower current price inside a linked card)
const STRIKE = /(old|was|strike|before|original|crossed|line-?through|prev|regular|rrp|list-?price)/i;
const PRICE_CLS = /(price|prc|amount|now|sell|current|final|sale|value)/i;
const CURRENCY = /(EGP|E£|LE|ج\.?\s?م|جنيه)/i;
const isStrike = (t: any) => ["DEL", "S", "STRIKE"].includes(t.tagName) || t.getAttribute?.("data-a-strike") === "true" ||
  (t.getAttribute?.("style") ?? "").replace(/ /g, "").includes("line-through") ||
  STRIKE.test(t.getAttribute?.("class") ?? "") || STRIKE.test(t.getAttribute?.("data-qa") ?? "");
const priceOf = (t: any) => { const s = txt(t); if (s.length > 40) return null; const p = price(s); return p && p >= 1 ? p : null; };
function defaultId(url: string) {
  const path = url.split("://").pop()!.split("/").slice(1).join("/").split("?")[0].replace(/\/$/, "");
  const segs = path.split("/").filter((s) => /\d/.test(s) && s.length >= 4);
  return segs.length ? segs[segs.length - 1] : stableHash(path);
}
function stableHash(s: string) { let h = 0; for (const ch of s) h = (Math.imul(h, 31) + ch.charCodeAt(0)) >>> 0; return h.toString(16); }

export function generic(html: string, store: string, base: string, idOf = defaultId, d = doc(html)): Item[] {
  const out = new Map<string, Item>();
  for (const s of d.querySelectorAll("*")) {
    if (!isStrike(s) || s.querySelectorAll("*").length > 3) continue;
    const old = priceOf(s);
    if (!old) continue;
    let card = s.parentElement, depth = 0;
    while (card && depth < 10 && !((card.matches("a[href]") || card.querySelector("a[href]")) && card.querySelector("img"))) { card = card.parentElement; depth++; }
    if (!card || txt(card).length > 1500) continue;
    const a = card.matches("a[href]") ? card : card.querySelector("a[href]");
    const link = a ? abs(a.getAttribute("href"), base) : "";
    if (!link.startsWith("http")) continue;
    const prices: number[] = [];
    for (const e of card.querySelectorAll("*")) {
      if (e === s || isStrike(e) || e.closest?.("del,s,strike") || [...e.children].some(isStrike)) continue;
      let p2 = e.parentElement, struck = false;
      while (p2 && p2 !== card) { if (isStrike(p2)) { struck = true; break; } p2 = p2.parentElement; }
      if (struck) continue;
      const own = [...e.childNodes].filter((n: any) => n.nodeType === 3).map((n: any) => n.textContent).join("");
      if (PRICE_CLS.test(e.getAttribute("class") ?? "") || PRICE_CLS.test(e.getAttribute("data-qa") ?? "") || CURRENCY.test(own)) {
        const p = priceOf(e);
        if (p && old * 0.1 < p && p < old) prices.push(p);   // > 90% off from a card = almost always a misread number
      }
    }
    if (!prices.length) continue;
    const img = card.querySelector("img");
    let image: string | null = null;
    for (const attr of ["data-src", "src", "data-lazy-src"]) {
      const v = img?.getAttribute(attr) ? abs(img.getAttribute(attr), base) : "";
      if (v.startsWith("http")) { image = v; break; }
    }
    const names = [...card.querySelectorAll("[class*=title], [class*=name], [data-qa*=name], [data-qa*=title], h2, h3, h4")].map(txt);
    const cands = [names.sort((x, y) => y.length - x.length)[0], img?.getAttribute("alt"), card.querySelector("a[title]")?.getAttribute("title")];
    const title = cands.map((c) => (c ?? "").trim()).find((c) => c.length >= 4);
    if (!title) continue;
    const id = `${store}:${idOf(link)}-${stableHash(title.toLowerCase())}`;
    if (!out.has(id)) out.set(id, { id, store, title, url: link.split("#")[0], image_url: image, current_price: Math.min(...prices), original_price: old });
  }
  return [...out.values()];
}

export const STORES: Record<string, { name: string; search: ((q: string) => string) | null; parse: (h: string) => [Item[], number]; deals: string[] }> = {
  amazon: { name: "أمازون مصر", search: amazonSearch, parse: parseAmazon, deals: AMAZON_DEALS },
  jumia: { name: "جوميا مصر", search: jumiaSearch, parse: parseJumia, deals: JUMIA_DEALS },
  noon: { name: "نون مصر", search: null, parse: parseNoon, deals: NOON_CATEGORIES },
  townteam: { name: "تاون تيم", search: null, parse: parseTownTeam, deals: ["https://townteam.com/collections/all"] },
};
