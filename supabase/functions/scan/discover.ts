// Discovery helpers for the scan queue: robots.txt rules, link extraction (categories + next pages), fingerprints.
// Pure functions (unit-tested); network/DB access stays in index.ts.

export type NewTarget = { url: string; kind: "category" | "page"; label?: string; depth: number };

// ───────── robots.txt (RFC 9309: groups, * and $ wildcards, longest match wins, Allow wins ties)
export class Robots {
  rules: [string, string][] = [];
  constructor(text: string | null) {
    if (!text) return;
    let star = false, prevUa = false;
    for (const raw of text.split(/\r?\n/)) {
      const line = raw.split("#")[0].trim();
      const i = line.indexOf(":");
      if (i < 0) continue;
      const k = line.slice(0, i).trim().toLowerCase(), v = line.slice(i + 1).trim();
      if (k === "user-agent") { if (!prevUa) star = false; star = star || v === "*"; prevUa = true; continue; }
      prevUa = false;
      if (star && (k === "allow" || k === "disallow") && v) this.rules.push([k, v]);
    }
  }
  private static rx(rule: string) {
    const end = rule.endsWith("$");
    const body = rule.replace(/\$$/, "").split("*").map((p) => p.replace(/[.+?^${}()|[\]\\]/g, "\\$&")).join(".*");
    return new RegExp("^" + body + (end ? "$" : ""));
  }
  ok(url: string): boolean {
    const u = new URL(url);
    const path = (u.pathname || "/") + (u.search || "");
    let best: [string, string] | null = null;
    for (const r of this.rules) {
      if (Robots.rx(r[1]).test(path) && (!best || r[1].length > best[1].length || (r[1].length === best[1].length && r[0] === "allow"))) best = r;
    }
    return !best || best[0] === "allow";
  }
}

/** Order-independent fingerprint of what a page lists (ids + prices): unchanged page → back off. */
export function fingerprint(items: { id: string; current_price: number }[]): string {
  const s = items.map((i) => `${i.id}:${Math.round(i.current_price)}`).sort().join("|");
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619) >>> 0; }
  return `${items.length}-${h.toString(16)}`;
}

const hrefs = (html: string) => [...new Set([...html.matchAll(/href="([^"]+)"/g)].map((m) => m[1].replace(/&amp;/g, "&")))];
const MAX_PAGES: Record<string, number> = { amazon: 7, noon: 10, townteam: 15 };

function pageOf(u: URL) { return Number(u.searchParams.get("page") ?? "1") || 1; }

/** Next page of a listing, if this one showed products. */
export function nextPage(store: string, url: string, items: number, newEvents: number, depth: number): NewTarget | null {
  const u = new URL(url);
  const p = pageOf(u);
  if (items === 0 || p >= (MAX_PAGES[store] ?? 5)) return null;
  // keep paging while pages still bring something new, or always for the first 3 pages
  if (p >= 3 && newEvents === 0) return null;
  u.searchParams.set("page", String(p + 1));
  return { url: u.toString(), kind: "page", depth, label: `page ${p + 1}` };
}

/** Category links found on a page (same store, allowed by robots, normalised, no product/search/cart pages). */
export function categoryLinks(store: string, html: string, depth: number, robots: Robots): NewTarget[] {
  if (depth >= 3) return [];
  const out = new Map<string, NewTarget>();
  for (const h of hrefs(html)) {
    let url: URL | null = null;
    try {
      url = new URL(h, store === "amazon" ? "https://www.amazon.eg/" : store === "noon" ? "https://www.noon.com/" : "https://townteam.com/");
    } catch { continue; }
    let t: string | null = null;
    if (store === "amazon") {
      // department / category refinements of a discounted listing: /s?...rh=n:<node>...
      if (url.hostname !== "www.amazon.eg" || url.pathname !== "/s") continue;
      const rh = url.searchParams.get("rh") ?? "";
      if (!/n:\d+/.test(rh)) continue;
      const n = new URL("https://www.amazon.eg/s");
      n.searchParams.set("rh", rh.includes("p_8:") || rh.includes("p_n_pct-off") ? rh : rh);
      const i = url.searchParams.get("i"); if (i) n.searchParams.set("i", i);
      n.searchParams.set("pct-off", "10-");
      n.searchParams.set("s", "date-desc-rank");
      n.searchParams.set("language", "en_AE");
      t = n.toString();
    } else if (store === "noon") {
      if (url.hostname !== "www.noon.com" || !/^\/egypt-en\/[a-z0-9][a-z0-9\-_/]+\/$/i.test(url.pathname)) continue;
      if (/\/p\/|\/search|\/cart|\/wishlist|\/checkout|\/account|\/seller|\/brands?\/|\/sell|\/help|\/about|\/careers|\/blog/i.test(url.pathname)) continue;
      t = `https://www.noon.com${url.pathname}?limit=50`;
    } else if (store === "townteam") {
      if (!/townteam\.com$/.test(url.hostname) || !/^\/collections\/[a-z0-9\-]+$/i.test(url.pathname)) continue;
      t = `https://townteam.com${url.pathname}`;
    }
    if (t && robots.ok(t) && !out.has(t)) out.set(t, { url: t, kind: "category", depth: depth + 1, label: decodeURIComponent(url.pathname.split("/").filter(Boolean).pop() ?? "") });
    if (out.size >= 60) break;
  }
  return [...out.values()];
}
