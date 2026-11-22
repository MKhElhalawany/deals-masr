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

    sent = {"n": 0}

    def probe(store, url, robots: Robots | None, why: str):
        if sent["n"] >= 12 or sum(1 for r in RAW.get(store, []) if "skipped" not in r) >= 5:   # hard budget
            RAW.setdefault(store, []).append({"url": url, "skipped": "budget reached", "why": why})
            return None
        if robots is not None and not robots.ok(url):
            RAW.setdefault(store, []).append({"url": url, "skipped": "disallowed by robots.txt", "why": why})
            return None
        sent["n"] += 1
        r = c.post(fn, headers={"x-cron-secret": secret}, json={"job": "probe", "url": url}).json()
        r["why"] = why
        RAW.setdefault(store, []).append(r)
        time.sleep(GAP)
        return r

    def robots_of(store, origin):
        r = probe(store, f"{origin}/robots.txt", None, "robots.txt")
        return r, Robots(r.get("robots") if r and r.get("status") == 200 else None)

    # ── Follow-up probe (run once): pages the first run skipped by mistake + full robots.txt where it was cut at 4 KB.
    # Budget ≤12 requests, ≤5 per store, ≥4 s apart, no retries. robots.txt already complete → read from tools/probe_cache.
    def cached(store):
        p = os.path.join(os.path.dirname(__file__), "probe_cache", f"{store}_robots.txt")
        return Robots(open(p).read()) if os.path.exists(p) else None

    # Amazon (1): full robots.txt only
    robots_of("amazon", "https://www.amazon.eg")

    # Noon (5): full robots, homepage category links, one category page, its page 2, discount sort
    _, rb = robots_of("noon", "https://www.noon.com")
    probe("noon", "https://www.noon.com/egypt-en/", rb, "homepage category links")
    cat = "https://www.noon.com/egypt-en/electronics-and-mobiles/"
    probe("noon", cat + "?limit=50", rb, "category page 1")
    probe("noon", cat + "?limit=50&page=2", rb, "category page 2")
    probe("noon", cat + "?sort[by]=discount_percent&sort[dir]=desc&limit=50", rb, "category sorted by discount")

    # DeFacto (≤2): homepage + one sale/category link from it (robots cached, complete)
    rb = cached("defacto")
    h = probe("defacto", "https://www.defacto.com/en-eg", rb, "homepage")
    sale = next((l for l in (h or {}).get("linkSample", []) if "/en-eg/" in l and any(w in l.lower() for w in ("sale", "outlet", "indirim", "discount", "offer", "firsat"))), None)
    if sale:
        probe("defacto", "https://www.defacto.com" + sale, rb, "sale page from homepage")

    # LC Waikiki (2): full robots + homepage
    _, rb = robots_of("lcwaikiki", "https://www.lcwaikiki.eg")
    probe("lcwaikiki", "https://www.lcwaikiki.eg/en", rb, "homepage")

    # Town Team (≤2): homepage + a sale collection page if linked (robots cached, complete)
    rb = cached("townteam")
    h = probe("townteam", "https://townteam.com/", rb, "homepage")
    sale = next((l for l in (h or {}).get("linkSample", []) if l.startswith("/collections/") and any(w in l.lower() for w in ("sale", "offer", "discount", "clearance"))), None)
    if sale:
        probe("townteam", "https://townteam.com" + sale, rb, "sale collection from homepage")

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
            pz = r.get("parsed") or {}
            if pz.get("items") is not None: note.append(f"parser: {pz['items']} منتج، {pz['deals10']} خصم ≥10%")
            REPORT.append(f"| {store} | {r['why']} ({urlsplit(r.get('url','')).path or '/'}) | {r.get('status', '—')} | {r.get('bytes', 0) // 1024} KB | {'⛔' if r.get('challenge') else '—'} | {' · '.join(note)[:220]} |")
    REPORT.append(f"\nإجمالي الطلبات للمتاجر: {total}")
    text = "\n".join(REPORT)
    print(text)
    print("::group::raw probe results (no secrets)\n" + json.dumps(RAW, ensure_ascii=False, indent=1)[:60000] + "\n::endgroup::")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        open(os.environ["GITHUB_STEP_SUMMARY"], "a").write(text + "\n")


if __name__ == "__main__":
    main()
