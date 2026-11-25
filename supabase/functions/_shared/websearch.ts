// Prices of the same product anywhere on the web (Egyptian shops), free:
//  1) Gemini with Google Search grounding (free daily allowance) reads the live web results,
//  2) Google Programmable Search JSON API (100 free queries/day) when GOOGLE_CSE_KEY + GOOGLE_CSE_CX are set,
//     then each shop page is read (robots.txt respected) for its schema.org price.
import { gemini } from "./gemini.ts";
import { Robots } from "./robots.ts";

export let lastRaw = "";
export type WebOffer = { store: string; title: string; url: string; price: number; image_url: string | null };
const CSE_KEY = Deno.env.get("GOOGLE_CSE_KEY") ?? "", CSE_CX = Deno.env.get("GOOGLE_CSE_CX") ?? "";
const UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
const host = (u: string) => { try { return new URL(u).hostname.replace(/^www\./, ""); } catch { return ""; } };
const egpNum = (s: string) => {
  const m = s.replace(/[٠-٩]/g, (d) => String("٠١٢٣٤٥٦٧٨٩".indexOf(d))).replace(/,/g, "").match(/(\d{2,7}(?:\.\d{1,2})?)\s*(?:EGP|LE|L\.E|ج\.?\s?م|جنيه)|(?:EGP|LE|ج\.?\s?م)\s*(\d{2,7}(?:\.\d{1,2})?)/i);
  return m ? Number(m[1] ?? m[2]) : NaN;
};

async function viaGemini(product: string, price: number): Promise<WebOffer[]> {
  const g = await gemini(
    "You are a price researcher for Egypt. Use Google Search to find online shops in Egypt that sell EXACTLY this product " +
    "(same brand, model and size) right now, and their current price in EGP. Reply ONLY with a JSON array (max 8) of " +
    '{"store": shop name, "url": product page URL, "price": number in EGP, "title": product name on that page}. ' +
    "Only include prices you actually saw in the search results. If none, reply [].",
    [{ role: "user", text: `Product: ${product}\nCurrent price we know: ${price} EGP` }],
    { search: true, maxTokens: 1200, temperature: 0 });
  if (!g) return [];
  lastRaw = g.text.slice(0, 300) + " [" + g.model + "]";
  try {
    const arr = JSON.parse(g.text.slice(g.text.indexOf("["), g.text.lastIndexOf("]") + 1));
    return (Array.isArray(arr) ? arr : []).map((o: any) => ({ store: String(o.store ?? host(String(o.url))).slice(0, 40),
      title: String(o.title ?? product).slice(0, 200), url: String(o.url ?? ""), price: Number(o.price), image_url: null }))
      .filter((o) => o.url.startsWith("https://") && o.price > 0 && !/vertexaisearch|google\./.test(host(o.url)));
  } catch { return []; }
}

const robotsCache = new Map<string, Robots>();
async function pagePrice(url: string): Promise<{ price: number; title: string; image: string | null } | null> {
  const h = host(url); if (!h) return null;
  let rb = robotsCache.get(h);
  if (!rb) {
    const r = await fetch(`https://${new URL(url).host}/robots.txt`, { signal: AbortSignal.timeout(4000), headers: { "User-Agent": UA } }).catch(() => null);
    if (!r || (!r.ok && r.status >= 500)) return null;
    rb = new Robots(r.ok ? await r.text() : ""); robotsCache.set(h, rb);
  }
  if (!rb.ok(url)) return null;
  const r = await fetch(url, { signal: AbortSignal.timeout(6000), headers: { "User-Agent": UA, "Accept-Language": "ar-EG,en;q=0.8" } }).catch(() => null);
  if (!r?.ok) return null;
  const html = (await r.text()).slice(0, 1_500_000);
  const price = Number(html.match(/"price"\s*:\s*"?(\d+(?:\.\d+)?)"?[^{}]{0,200}"priceCurrency"\s*:\s*"EGP"/)?.[1]
    ?? html.match(/"priceCurrency"\s*:\s*"EGP"[^{}]{0,200}"price"\s*:\s*"?(\d+(?:\.\d+)?)/)?.[1]
    ?? html.match(/product:price:amount"\s+content="(\d+(?:\.\d+)?)"/)?.[1] ?? NaN);
  if (!(price > 0)) return null;
  return { price, title: html.match(/<title>([^<]{3,200})<\/title>/)?.[1]?.trim() ?? "", image: html.match(/og:image"\s+content="([^"]+)"/)?.[1] ?? null };
}

async function viaCse(product: string): Promise<WebOffer[]> {
  if (!CSE_KEY || !CSE_CX) return [];
  const r = await fetch(`https://www.googleapis.com/customsearch/v1?key=${CSE_KEY}&cx=${CSE_CX}&gl=eg&num=10&q=${encodeURIComponent(product + " سعر")}`,
    { signal: AbortSignal.timeout(8000) }).catch(() => null);
  if (!r?.ok) return [];
  const items: any[] = (await r.json()).items ?? [];
  const out: WebOffer[] = [];
  await Promise.all(items.slice(0, 10).map(async (it) => {
    const url = String(it.link ?? ""); if (!url.startsWith("https://")) return;
    const pm = it.pagemap ?? {};
    let price = Number(pm.offer?.[0]?.price ?? pm.product?.[0]?.price ?? pm.metatags?.[0]?.["product:price:amount"] ?? NaN);
    if (!(price > 0)) price = egpNum(`${it.title ?? ""} ${it.snippet ?? ""}`);
    let title = String(it.title ?? ""), image = pm.cse_image?.[0]?.src ?? null;
    if (!(price > 0)) { const p = await pagePrice(url).catch(() => null); if (p) { price = p.price; title = p.title || title; image = p.image ?? image; } }
    if (price > 0) out.push({ store: host(url), title, url, price, image_url: image });
  }));
  return out;
}

/** Offers for the product from across the web (deduplicated by URL). */
export async function webOffers(product: string, price: number): Promise<WebOffer[]> {
  const [a, b] = await Promise.all([viaGemini(product, price).catch(() => []), viaCse(product).catch(() => [])]);
  const seen = new Set<string>();
  return [...a, ...b].filter((o) => !seen.has(o.url) && seen.add(o.url));
}
export const webSearchConfigured = () => !!(CSE_KEY && CSE_CX);
