// Public Telegram channel preview pages (https://t.me/s/<channel>) → compact posts for the "عروض مجنونة" section.
export type TgPost = { post_id: number; text: string; url: string | null; image_url: string | null;
  price: number | null; old_price: number | null; posted_at: string | null };

const AR_DIGITS = "٠١٢٣٤٥٦٧٨٩";
const latinDigits = (s: string) => s.replace(/[٠-٩]/g, (d) => String(AR_DIGITS.indexOf(d))).replace(/٫/g, ".").replace(/٬/g, ",");
const decode = (s: string) => s.replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, "<")
  .replace(/&gt;/g, ">").replace(/&nbsp;/g, " ").replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)));

/** "price before/after" from free Arabic/English text; null when the post does not say both clearly. */
export function prices(text: string): { price: number | null; old_price: number | null } {
  const t = latinDigits(text);
  const num = (s: string) => Number(s.replace(/,/g, ""));
  const NUM = String.raw`(\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?`;
  const old = t.match(new RegExp(String.raw`(?:بدل(?:اً|ا)?\s*من|بدل|قبل(?:\s*الخصم)?|كان(?:\s*ب)?|السعر\s*الأصلي|was|instead\s*of)\s*:?\s*` + NUM, "i"));
  const now = t.match(new RegExp(String.raw`(?:السعر\s*(?:بعد\s*الخصم|الحالي|دلوقتي)?|بسعر|ب\s*|بـ\s*|now|price|الآن|بقى|بقا)\s*:?\s*` + NUM, "i"));
  let p = now ? num(now[1]) : null, o = old ? num(old[1]) : null;
  if (p == null || o == null) {   // fall back to the amounts written with a currency
    const money = [...t.matchAll(new RegExp(NUM + String.raw`\s*(?:ج\.?م|جنيه|ج\b|EGP|LE|L\.E)`, "gi"))].map((m) => num(m[1])).filter((x) => x >= 1);
    if (money.length >= 2) { p = Math.min(...money.slice(0, 3)); o = Math.max(...money.slice(0, 3)); }
  }
  if (p == null || o == null || !(o > p) || p < 1 || (o - p) / o > 0.97) return { price: null, old_price: null };
  return { price: p, old_price: o };
}

export function parseTelegram(html: string, channel: string): TgPost[] {
  const marks = [...html.matchAll(new RegExp(`data-post="${channel}/(\\d+)"`, "g"))];
  return marks.map((m, i) => {
    const chunk = html.slice(m.index!, marks[i + 1]?.index ?? html.length);
    const body = chunk.match(/class="tgme_widget_message_text[^"]*"[^>]*>([\s\S]*?)<\/div>/)?.[1] ?? "";
    const text = decode(body.replace(/<br\s*\/?>/gi, "\n").replace(/<[^>]+>/g, "")).replace(/[ \t]+/g, " ").replace(/\n{3,}/g, "\n\n").trim();
    const links = [...body.matchAll(/href="(https?:\/\/[^"]+)"/g)].map((x) => decode(x[1])).filter((u) => !/^https?:\/\/(t\.me|telegram\.me)\//.test(u));
    const preview = chunk.match(/class="tgme_widget_message_link_preview"[^>]*href="(https?:\/\/[^"]+)"/)?.[1];
    const img = chunk.match(/tgme_widget_message_(?:photo_wrap|link_preview_image|video_thumb)[^>]*background-image:url\('([^']+)'\)/)?.[1]
      ?? chunk.match(/background-image:url\('(https:\/\/[^']*(?:telesco\.pe|cdn-telegram\.org)[^']+)'\)/)?.[1] ?? null;
    const when = chunk.match(/<time[^>]*datetime="([^"]+)"/)?.[1] ?? null;
    return { post_id: Number(m[1]), text: text.slice(0, 2000), url: links[0] ?? (preview ? decode(preview) : null),
      image_url: img && /^https:\/\//.test(img) ? img : null, posted_at: when, ...prices(text) };
  }).filter((p) => p.text || p.image_url);
}
