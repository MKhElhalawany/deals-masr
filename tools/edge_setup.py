#!/usr/bin/env python3
"""Sets up the free 24/7 scanner on Supabase itself (no VM, no card): run AFTER `supabase functions deploy scan`.

  • function secrets: CRON_SECRET (random, also kept in Supabase Vault), SERVICE_KEY, optional FIREBASE_SERVICE_ACCOUNT
  • pg_cron: every 30 s per store → app_private.call_scan() → pg_net → /functions/v1/scan (secret header)
  • triggers: new search request / SHEIN quote / order status → instant call
  • live check: calls the function once per store and reports real timings (fetch from the store, save, push)
Never prints secrets.
"""
from __future__ import annotations

import json
import os
import secrets
import sys
import time

import httpx

sys.path.insert(0, os.path.dirname(__file__))
import supabase_setup as S  # noqa: E402

STORES = ["amazon", "jumia", "noon", "townteam"]
OUT: list[str] = []


def out(k, v):
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as f:
            if "\n" in v:
                f.write(f"{k}<<EDGE_EOF\n{v}\nEDGE_EOF\n")
            else:
                f.write(f"{k}={v}\n")


def q(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def main():
    if not S.TOKEN:
        print("::notice::no SUPABASE_ACCESS_TOKEN — skipping edge scanner setup")
        return
    ref = S.existing_ref()
    url = f"https://{ref}.supabase.co"
    key = S.server_key(ref)

    # 1) CRON secret: stable across runs (stored in Vault), random on first run
    S.sql(ref, "create extension if not exists pg_net; create extension if not exists pg_cron;")
    rows = S.sql(ref, "select decrypted_secret as s from vault.decrypted_secrets where name = 'scan_cron_secret'") or []
    cron_secret = rows[0]["s"] if rows else None
    if not cron_secret:
        cron_secret = secrets.token_urlsafe(32)
        print(f"::add-mask::{cron_secret}", flush=True)
        S.sql(ref, f"select vault.create_secret({q(cron_secret)}, 'scan_cron_secret', 'auth header for the scan edge function')")
    print(f"::add-mask::{cron_secret}", flush=True)

    # 2) function secrets (Management API)
    fsecrets = [{"name": "CRON_SECRET", "value": cron_secret}, {"name": "SERVICE_KEY", "value": key}]
    gk = os.environ.get("GEMINI_API_KEY", "").strip()
    if gk:
        print(f"::add-mask::{gk}", flush=True)
        fsecrets.append({"name": "GEMINI_API_KEY", "value": gk})
    print(f"Gemini key: {'present ✓' if gk else 'MISSING (add the GEMINI_API_KEY secret on GitHub)'}")
    fsa = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "").strip()
    if fsa:
        import base64
        fsecrets.append({"name": "FIREBASE_SERVICE_ACCOUNT", "value": base64.b64encode(fsa.encode()).decode()})
    st, body = S.http("POST", f"{S.API}/projects/{ref}/secrets", fsecrets, {"Authorization": f"Bearer {S.TOKEN}"})
    if st >= 400:
        S.fail(f"Could not set edge function secrets ({st}). The token needs 'Edge Functions: write'.")

    # 3) scheduler + instant triggers
    S.sql(ref, f"""
create or replace function app_private.call_scan(p_body jsonb) returns void
language plpgsql security definer set search_path = public, extensions as $f$
declare s text;
begin
  select decrypted_secret into s from vault.decrypted_secrets where name = 'scan_cron_secret';
  if s is null then return; end if;
  perform net.http_post(url := {q(url + '/functions/v1/scan')}, body := p_body,
    headers := jsonb_build_object('Content-Type', 'application/json', 'x-cron-secret', s), timeout_milliseconds := 60000);
exception when others then raise warning 'call_scan failed: %', sqlerrm;
end $f$;
revoke all on function app_private.call_scan(jsonb) from public, anon, authenticated;

create or replace function public.tg_call_scan() returns trigger
language plpgsql security definer set search_path = public as $f$
begin
  if tg_table_name = 'search_requests' then perform app_private.call_scan(jsonb_build_object('job', 'search', 'id', new.id));
  elsif tg_table_name = 'shein_quotes' then perform app_private.call_scan(jsonb_build_object('job', 'shein', 'id', new.id));
  elsif tg_table_name = 'orders' and new.status in ('submitted', 'confirmed')
        and (tg_op = 'INSERT' or old.status is distinct from new.status) then
    perform app_private.call_scan(jsonb_build_object('job', 'order', 'id', new.id));
  end if;
  return null;
exception when others then return null;   -- never block the user's insert
end $f$;
revoke all on function public.tg_call_scan() from public, anon, authenticated;
drop trigger if exists call_scan on public.search_requests;
create trigger call_scan after insert on public.search_requests for each row execute function public.tg_call_scan();
drop trigger if exists call_scan on public.shein_quotes;
create trigger call_scan after insert on public.shein_quotes for each row execute function public.tg_call_scan();
drop trigger if exists call_scan on public.orders;
create trigger call_scan after insert or update of status on public.orders for each row execute function public.tg_call_scan();
""")
    # free plan = 500k runs/month: noon every 20 s (2 pages) + amazon every 30 s ≈ 216k; jumia (Cloudflare-blocked) every 5 min
    schedules = {"amazon": "30 seconds", "noon": "20 seconds", "jumia": "*/5 * * * *", "townteam": "* * * * *"}
    for s, every in schedules.items():
        S.sql(ref, f"select cron.schedule('scan-{s}', '{every}', $c$select app_private.call_scan('{{\"job\":\"scan\",\"store\":\"{s}\"}}'::jsonb)$c$)")
    # discovery queue seeds (idempotent): store-wide discounted listings, sale pages, category entry points
    amz = "https://www.amazon.eg/s?i=specialty-aps&pct-off={p}-&s=date-desc-rank&language=en_AE"
    seeds = [("amazon", amz.format(p=50), "seed", "كل الأقسام 50%+", 30), ("amazon", amz.format(p=30), "seed", "كل الأقسام 30%+", 30),
             ("amazon", amz.format(p=10), "seed", "كل الأقسام 10%+", 30), ("amazon", "https://www.amazon.eg/deals?language=en_AE", "seed", "Today's Deals", 60)]
    kws = (S.sql(ref, "select value from app_config where key = 'scrape_keywords'") or [{}])[0].get("value") or []
    if isinstance(kws, str):
        kws = json.loads(kws)
    from urllib.parse import quote_plus
    seeds += [("amazon", f"https://www.amazon.eg/s?k={quote_plus(k)}&pct-off=10-&language=en_AE", "keyword", k, 300) for k in kws]
    noon = ["", "electronics-and-mobiles/", "fashion/eg-fashion-men-cat/", "fashion/eg-fashion-women-cat/", "eg-fashion-footwear/", "beauty/",
            "sports-eg/", "eg-yellow-friday-sale-offers/", "eg-november26-clearance-yfs/", "eg-nov26-yf-fash-clearance-w1/", "Women-fashion-deals/"]
    seeds += [("noon", f"https://www.noon.com/egypt-en/{p}" + ("?limit=50" if p else ""), "seed", p.strip("/") or "home", 60) for p in noon]
    seeds += [("townteam", u, "seed", u.rsplit("/", 1)[-1] or "home", 60) for u in
              ("https://townteam.com/", "https://townteam.com/collections/all", "https://townteam.com/collections/spring-offers")]
    values = ",".join(f"({q(st)},{q(u)},{q(k)},{q(l)},0,{mi},{mi})" for st, u, k, l, mi in seeds)
    S.sql(ref, "insert into public.scan_targets (store, url, kind, label, depth, interval_sec, min_interval) values "
               f"{values} on conflict (store, url) do nothing")
    S.sql(ref, "insert into public.app_config (key, value) values ('scanner_mode', '\"queue\"') on conflict (key) do nothing")
    # one-time data fix: Town Team prices were read from page layout (misreads such as 30 instead of 349) → hide them;
    # the JSON reader re-adds every real Town Team deal within minutes. Also hide >90% deals (almost always misreads).
    if not S.sql(ref, "select 1 from app_config where key = 'cleanup_v1'"):
        fixed = S.sql(ref, "with x as (update public.deals set active = false, updated_at = now() where active and "
                           "(store_id = 'townteam' or discount_pct > 90) returning 1) select count(*) as n from x")
        S.sql(ref, "update public.scan_targets set next_run_at = now(), fingerprint = null where store = 'townteam'")
        S.sql(ref, "insert into app_config (key, value) values ('cleanup_v1', 'true')")
        print(f"cleanup: hid {fixed[0]['n']} misread deals (Town Team layout prices, >90%)")
    # one-time data fix 2: noon deals read from page layout (ids "noon:<sku>-<hash>") had wrong prices → hide them
    if not S.sql(ref, "select 1 from app_config where key = 'cleanup_v2'"):
        fixed = S.sql(ref, "with x as (update public.deals set active = false, updated_at = now() where active and store_id = 'noon' "
                           "and id ~ '^noon:.+-[0-9a-f]{1,8}$' returning 1) select count(*) as n from x")
        S.sql(ref, "update public.scan_targets set fingerprint = null where store = 'noon'")
        S.sql(ref, "insert into app_config (key, value) values ('cleanup_v2', 'true')")
        print(f"cleanup v2: hid {fixed[0]['n']} noon deals with layout-read prices")
    qstat = S.sql(ref, "select store, count(*) as n from public.scan_targets group by store order by store") or []
    print("scan targets:", ", ".join(f"{r['store']} {r['n']}" for r in qstat))

    # Phase 0: once a day (03:17 Cairo = 01:17 UTC) price history older than 90 days → one row per product per day
    S.sql(ref, "select cron.schedule('compact-price-history', '17 1 * * *', $c$select public.compact_price_history(90)$c$)")
    jobs = S.sql(ref, "select jobname, schedule from cron.job where jobname like 'scan-%' order by 1") or []
    print("cron jobs:", ", ".join(f"{j['jobname']} ({j['schedule']})" for j in jobs))

    # 4) live check from Supabase's servers to the stores (real numbers, no phone involved)
    time.sleep(5)
    OUT.append("### 🛰️ سيرفر Supabase (مجاني، بدون فيزا) — فحص حقيقي للمتاجر")
    OUT.append(f"الجدولة: نون كل 20 ث (صفحتين)، أمازون كل 30 ث، جوميا كل 5 دقايق ({len(jobs)} مهام) · الإشعارات الفورية: {'✅' if fsa else '⏳ مش متفعّلة'}")
    OUT.append("| المتجر | HTTP | منتجات اتقرت | عروض اتحفظت | وقت التشغيل | اكتشاف | الإجمالي |\n|---|---|---|---|---|---|---|")
    c = httpx.Client(timeout=90)
    working = 0
    diag: list[str] = []
    for s in STORES:
        t = time.perf_counter()
        try:
            r = c.post(f"{url}/functions/v1/scan", headers={"x-cron-secret": cron_secret}, json={"job": "scan", "store": s})
            total = (time.perf_counter() - t) * 1000
            j = r.json() if r.headers.get("content-type", "").startswith("application/json") else {"error": f"HTTP {r.status_code}"}
        except Exception as e:
            total, j = 0, {"error": str(e)}
        if "skipped" in j or "error" in j:
            diag.append(s)
        if "skipped" in j:
            OUT.append(f"| {s} | — | اتخطى: {j['skipped']} | | | | |")
        elif "error" in j:
            OUT.append(f"| {s} | ❌ | {str(j['error'])[:80]} | | | | |")
        else:
            working += 1 if j.get("cards") else 0
            if j.get("cards") and not j.get("accepted"):
                diag.append(s)
            OUT.append(f"| {s} | {j.get('code')} | {j.get('cards')} | {j.get('accepted')} | {j.get('fetch_ms')} ms | +{j.get('added', 0)} صفحة | {total:.0f} ms |")
    diag = sorted(set(diag + [x for x in os.environ.get("EDGE_DIAG", "").split(",") if x]))
    for s in diag:   # page refused / read without deals → print what the store returned (product HTML only) for diagnosis
        try:
            d = c.post(f"{url}/functions/v1/scan", headers={"x-cron-secret": cron_secret}, json={"job": "debug", "store": s}).json()
            print(f"::group::{s} sample (HTTP {d.get('code')}, {d.get('length')} bytes) {d.get('error') or d.get('skipped') or ''}\n{d.get('sample', '')}\n::endgroup::")
        except Exception as e:
            print(f"debug {s} failed: {e}")
    OUT.append(f"\nمتاجر شغالة فعلاً من سيرفر Supabase: **{working}/{len(STORES)}**. "
               "أقصى تأخير للاكتشاف ≈ 20 ث (دورة الفحص) + زمن الصفحة؛ صفحة عروض أمازون بتتراجع كل 20 ث.")
    # 5) real database write rates over 3 minutes with the scanner running (no extra store requests)
    q_stats = ("select relname as t, n_tup_ins as ins, n_tup_upd as upd, n_tup_del as del from pg_stat_user_tables "
               "where schemaname = 'public' and relname in ('deals','deal_seen','price_history','user_notifications')")
    try:
        a = {r["t"]: r for r in S.sql(ref, q_stats)}
        t0 = time.time()
        noon_runs: dict[str, str] = {}   # scheduled noon runs seen during the window (DB reads only, no store requests)
        while time.time() - t0 < 180:
            for r in S.sql(ref, "select message, last_run from scraper_status where store = 'noon'") or []:
                noon_runs[r["last_run"]] = r["message"]
            time.sleep(15)
        b = {r["t"]: r for r in S.sql(ref, q_stats)}
        mins = (time.time() - t0) / 60
        sizes = {r["t"]: r for r in S.sql(ref, "select relname as t, n_live_tup as rows, pg_total_relation_size(relid) as bytes "
                                               "from pg_stat_user_tables where schemaname='public' and relname in ('deals','deal_seen','price_history')")}
        OUT.append("\n### 🗄️ معدل الكتابة الفعلي في قاعدة البيانات (3 دقايق)")
        OUT.append("| الجدول | إضافة/دقيقة | تعديل/دقيقة | صفوف | الحجم |\n|---|---|---|---|---|")
        for t in ("deals", "deal_seen", "price_history", "user_notifications"):
            if t in a and t in b:
                ins = (int(b[t]["ins"]) - int(a[t]["ins"])) / mins
                upd = (int(b[t]["upd"]) - int(a[t]["upd"])) / mins
                sz = sizes.get(t, {})
                OUT.append(f"| {t} | {ins:.1f} | {upd:.1f} | {sz.get('rows', '')} | {int(sz.get('bytes', 0)) // 1024} KB |")
        cov = S.sql(ref, "select store, count(*) as targets, count(*) filter (where last_run_at is not null) as scanned, "
                         "coalesce(sum(last_deals) filter (where last_run_at > now() - interval '10 minutes'),0) as deals_10m, "
                         "count(*) filter (where kind in ('category','page')) as discovered from public.scan_targets group by store order by store") or []
        OUT.append("\n### 🧭 الاكتشاف (طابور المسح)")
        OUT.append("| المتجر | صفحات في الطابور | اتمسحت | اتكتشفت تلقائياً | عروض في آخر 10 دقايق |\n|---|---|---|---|---|")
        for r in cov:
            OUT.append(f"| {r['store']} | {r['targets']} | {r['scanned']} | {r['discovered']} | {r['deals_10m']} |")
        dz = S.sql(ref, "select store_id, count(*) as n, count(*) filter (where updated_at > now() - interval '1 hour') as h1, "
                        "count(*) filter (where image_url is not null) as img, max(discount_pct) as maxpct from public.deals where active group by store_id order by store_id") or []
        OUT.append("\n| المتجر | عروض نشطة | اتحدثت آخر ساعة | بصور | أعلى خصم |\n|---|---|---|---|---|")
        for r in dz:
            OUT.append(f"| {r['store_id']} | {r['n']} | {r['h1']} | {r['img']} | {r['maxpct']}% |")
        OUT.append("\n### نون — التشغيلات المجدولة اللي اتسجلت في الـ 3 دقايق")
        for when, msg in sorted(noon_runs.items()):
            OUT.append(f"- {str(when)[11:19]} UTC — {msg}")
    except Exception as e:
        OUT.append(f"\n(قياس قاعدة البيانات فشل: {e})")
    dq = os.path.join(os.path.dirname(__file__), "diag_queries.sql")
    if os.path.exists(dq):
        print("::group::diagnostic queries (read-only)")
        for line in open(dq):
            line = line.strip()
            if line.lower().startswith("select"):
                try:
                    print(">>", line[:120]); print(json.dumps(S.sql(ref, line), ensure_ascii=False, default=str)[:4000])
                except Exception as e:
                    print("!!", e)
        print("::endgroup::")
    text = "\n".join(OUT)
    print(text)
    out("report", text)
    out("working", str(working))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        open(os.environ["GITHUB_STEP_SUMMARY"], "a").write(text + "\n")


if __name__ == "__main__":
    main()
