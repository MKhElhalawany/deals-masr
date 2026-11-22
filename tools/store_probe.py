#!/usr/bin/env python3
"""Phase 0 — ONE-TIME, low-rate store probe (≤5 requests per store, ≥4 s apart, from Supabase's servers).

Respects robots.txt (User-agent: *): a disallowed URL is skipped, not fetched. No retries, no cookies, no
challenge solving: a Cloudflare/CAPTCHA page is simply reported as "blocked". Prints a markdown report.
Env: SUPABASE_ACCESS_TOKEN (to read the scan function's secret from Vault, never printed).
"""
from __future__ import annotations

import json
import os
import sys
import time
from urllib.parse import urlsplit

import httpx

sys.path.insert(0, os.path.dirname(__file__))
import supabase_setup as S  # noqa: E402

GAP = 4.0
REPORT: list[str] = []
RAW: dict[str, list] = {}


class Robots:
    """robots.txt rules for User-agent: * (consecutive User-agent lines share a group; * and $ wildcards;
    longest match wins, Allow wins ties) — the standard precedence (RFC 9309)."""

    def __init__(self, text: str | None):
        self.rules: list[tuple[str, str]] = []
        self.sitemaps: list[str] = []
        if not text:
            return
        in_star, prev_ua = False, False
        for line in text.splitlines():
            line = line.split("#", 1)[0].strip()
            if ":" not in line:
                continue
            k, v = (x.strip() for x in line.split(":", 1))
            k = k.lower()
            if k == "user-agent":
                if not prev_ua:
                    in_star = False
                in_star = in_star or v == "*"
                prev_ua = True
                continue
            prev_ua = False
            if k == "sitemap":
                self.sitemaps.append(v)
            elif in_star and k in ("allow", "disallow") and v:
                self.rules.append((k, v))

    @staticmethod
    def _rx(rule: str):
        import re
        end = rule.endswith("$")
        body = "".join(".*" if ch == "*" else re.escape(ch) for ch in rule.rstrip("$"))
        return re.compile("^" + body + ("$" if end else ""))

    def ok(self, url: str) -> bool:
        p = urlsplit(url)
        path = (p.path or "/") + (("?" + p.query) if p.query else "")
        best = None
        for k, v in self.rules:
            if self._rx(v).match(path) and (best is None or len(v) > len(best[1]) or (len(v) == len(best[1]) and k == "allow")):
                best = (k, v)
        return best is None or best[0] == "allow"


def main():
    ref = S.existing_ref()
    secret = (S.sql(ref, "select decrypted_secret as s from vault.decrypted_secrets where name = 'scan_cron_secret'") or [{}])[0].get("s")
    if not secret:
        S.fail("scan function secret not found (edge setup must run first)")
    print(f"::add-mask::{secret}", flush=True)
    fn = f"https://{ref}.supabase.co/functions/v1/scan"
    c = httpx.Client(timeout=60)

    def probe(store, url, robots: Robots | None, why: str):
        if robots is not None and not robots.ok(url):
            RAW.setdefault(store, []).append({"url": url, "skipped": "disallowed by robots.txt", "why": why})
            return None
        r = c.post(fn, headers={"x-cron-secret": secret}, json={"job": "probe", "url": url}).json()
        r["why"] = why
        RAW.setdefault(store, []).append(r)
        time.sleep(GAP)
        return r

    def robots_of(store, origin):
        r = probe(store, f"{origin}/robots.txt", None, "robots.txt")
        return r, Robots(r.get("robots") if r and r.get("status") == 200 else None)

    # Amazon
    _, rb = robots_of("amazon", "https://www.amazon.eg")
    home = probe("amazon", "https://www.amazon.eg/s?k=deals&language=en_AE", rb, "department list (search box)")
    deps = (home or {}).get("amazonDepartmentSample") or []
    dep = next((d for d in deps if d in ("electronics", "fashion", "kitchen", "beauty")), deps[1] if len(deps) > 1 else None)
    if dep:
        probe("amazon", f"https://www.amazon.eg/s?i={dep}&pct-off=30-&s=date-desc-rank&language=en_AE", rb, f"department deals page ({dep})")
    probe("amazon", "https://www.amazon.eg/s?i=specialty-aps&pct-off=30-&s=date-desc-rank&language=en_AE&page=2", rb, "pagination (page 2)")
    probe("amazon", "https://www.amazon.eg/deals?language=en_AE", rb, "Today's Deals page (JS-only?)")

    # Noon
    _, rb = robots_of("noon", "https://www.noon.com")
    probe("noon", "https://www.noon.com/egypt-en/", rb, "homepage category links")
    probe("noon", "https://www.noon.com/egypt-en/electronics-and-mobiles/?limit=50&page=2", rb, "category pagination (page 2)")
    probe("noon", "https://www.noon.com/egypt-en/electronics-and-mobiles/?sort[by]=discount_percent&sort[dir]=desc&limit=50", rb, "sort by discount")
    sm = next((s for s in rb.sitemaps if "eg" in s.lower()), rb.sitemaps[0] if rb.sitemaps else None)
    if sm:
        probe("noon", sm, rb, "sitemap")

    # Jumia: robots only (Cloudflare blocks pages; nothing else is attempted)
    robots_of("jumia", "https://www.jumia.com.eg")

    # DeFacto / LC Waikiki / Town Team
    for store, origin, homepage in [("defacto", "https://www.defacto.com", "https://www.defacto.com/en-eg"),
                                    ("lcwaikiki", "https://www.lcwaikiki.eg", "https://www.lcwaikiki.eg/en")]:
        _, rb = robots_of(store, origin)
        h = probe(store, homepage, rb, "homepage")
        if rb.sitemaps:
            probe(store, rb.sitemaps[0], rb, "sitemap")
        sale = next((l for l in (h or {}).get("linkSample", []) if any(w in l.lower() for w in ("sale", "outlet", "indirim", "discount", "offer"))), None)
        if sale:
            probe(store, origin + sale, rb, "sale page")
    for origin in ("https://townteam.com", "https://www.townteam.com.eg"):
        r, rb = robots_of("townteam", origin)
        if r and r.get("status") == 200 and not r.get("challenge"):
            probe("townteam", origin + "/", rb, "homepage")
            break

    # report
    REPORT.append("### 🔎 فحص المتاجر لمرة واحدة (من سيرفر Supabase، ≤5 طلبات لكل متجر، 4 ث بين الطلبات)")
    REPORT.append("| المتجر | الغرض | HTTP | حجم | حماية؟ | ملاحظات |\n|---|---|---|---|---|---|")
    total = 0
    for store, rows in RAW.items():
        for r in rows:
            if "skipped" in r:
                REPORT.append(f"| {store} | {r['why']} | — | — | — | اتخطى: ممنوع في robots.txt |"); continue
            total += 1
            m = r.get("markers") or {}
            note = []
            if r.get("error"): note.append(r["error"][:80])
            if "robots" in r: note.append(f"Disallow: {r['robots'].count('Disallow')} · Sitemap: {r['robots'].lower().count('sitemap:')}")
            if r.get("sitemapEntries") is not None: note.append(f"{r['sitemapEntries']} روابط في السايت ماب")
            if r.get("amazonDepartments"): note.append(f"{r['amazonDepartments']} قسم")
            if m: note.append(", ".join(f"{k}={v}" for k, v in m.items() if v))
            REPORT.append(f"| {store} | {r['why']} | {r.get('status', '—')} | {r.get('bytes', 0) // 1024} KB | {'⛔' if r.get('challenge') else '—'} | {' · '.join(note)[:220]} |")
    REPORT.append(f"\nإجمالي الطلبات للمتاجر: {total}")
    text = "\n".join(REPORT)
    print(text)
    print("::group::raw probe results (no secrets)\n" + json.dumps(RAW, ensure_ascii=False, indent=1)[:60000] + "\n::endgroup::")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        open(os.environ["GITHUB_STEP_SUMMARY"], "a").write(text + "\n")


if __name__ == "__main__":
    main()
