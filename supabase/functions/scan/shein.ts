// SHEIN (Saudi storefront) without a browser: best effort on the server HTML (port of parsers.py parse_shein).
export function goodsId(url: string): string | null {
  for (const re of [/-p-(\d{5,})(?:-cat-\d+)?\.html/i, /\/goods[-_/]?(?:detail\/)?(\d{5,})/i, /[?&](?:goods_id|goodsId|productId)=(\d{5,})/i]) {
    const m = url.match(re);
    if (m) return m[1];
  }
  try {
    const u = new URL(url);
    for (const k of ["url", "link", "redirect"]) {
      const v = u.searchParams.get(k);
      if (v) { const g = goodsId(decodeURIComponent(v)); if (g) return g; }
    }
  } catch { /* not a URL */ }
  return null;
}

function enclosing(s: string, idx: number, minLen: number): [number, number] | null {
  let depth = 0, i = idx;
  for (; i >= 0; i--) {
    if (s[i] === "}") depth++;
    else if (s[i] === "{") { if (depth === 0) break; depth--; }
  }
  if (i < 0) return null;
  let j = i, inStr = false; depth = 0;
  for (; j < s.length; j++) {
    const ch = s[j];
    if (ch === '"' && s[j - 1] !== "\\") inStr = !inStr;
    else if (!inStr) { if (ch === "{") depth++; else if (ch === "}") { depth--; if (depth === 0) break; } }
  }
  if (j >= s.length) return null;
  if (j - i >= minLen || i === 0) return [i, j];
  const parent = enclosing(s, i - 1, minLen);
  return !parent || parent[1] - parent[0] > 150_000 ? [i, j] : parent;
}

export function parseShein(html: string, gid: string): { title: string; image_url: string | null; retail_sar: number } {
  const prices: number[] = [];
  let title: string | null = null, image: string | null = null, wrong = false;
  const re = new RegExp(`"goods_id"\\s*:\\s*"?${gid}"?`, "g");
  let n = 0;
  for (const m of html.matchAll(re)) {
    if (n++ >= 40) break;
    const r = enclosing(html, m.index!, 1500);
    if (!r) continue;
    const block = html.slice(r[0], r[1] + 1);
    for (const x of block.matchAll(/"retail(?:Price|_price)"\s*:\s*\{[^{}]*?"amountWithSymbol"\s*:\s*"([^"]*)"/g)) {
      if (/(EGP|E£|ج\.?\s?م|جنيه)/.test(x[1])) wrong = true;
    }
    for (const x of block.matchAll(/"retail(?:Price|_price)"\s*:\s*\{[^{}]*?"amount"\s*:\s*"?([\d.]+)"?/g)) {
      const v = parseFloat(x[1]); if (v > 0) prices.push(v);
    }
    title ??= block.match(/"goods_name"\s*:\s*"((?:[^"\\]|\\.)*)"/)?.[1] ? JSON.parse(`"${block.match(/"goods_name"\s*:\s*"((?:[^"\\]|\\.)*)"/)![1]}"`) : null;
    image ??= block.match(/"goods_img"\s*:\s*"([^"]+)"/)?.[1] ?? null;
  }
  if (wrong) throw new Error("الصفحة فتحت بأسعار مصر مش السعودية");
  if (!prices.length) throw new Error("مالقيناش السعر الأصلي في الصفحة");
  if (image?.startsWith("//")) image = "https:" + image;
  return { title: title ?? `منتج SHEIN ${gid}`, image_url: image, retail_sar: Math.max(...prices) };
}
