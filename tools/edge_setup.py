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

STORES = ["amazon", "jumia", "noon"]
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
    for s in STORES:
        S.sql(ref, f"select cron.schedule('scan-{s}', '30 seconds', $c$select app_private.call_scan('{{\"job\":\"scan\",\"store\":\"{s}\"}}'::jsonb)$c$)")
    jobs = S.sql(ref, "select jobname, schedule from cron.job where jobname like 'scan-%' order by 1") or []
    print("cron jobs:", ", ".join(f"{j['jobname']} ({j['schedule']})" for j in jobs))

    # 4) live check from Supabase's servers to the stores (real numbers, no phone involved)
    time.sleep(5)
    OUT.append("### 🛰️ سيرفر Supabase (مجاني، بدون فيزا) — فحص حقيقي للمتاجر")
    OUT.append(f"الجدولة: كل متجر كل 30 ث ({len(jobs)} مهام) · الإشعارات الفورية: {'✅' if fsa else '⏳ مش متفعّلة'}")
    OUT.append("| المتجر | HTTP | منتجات اتقرت | عروض اتحفظت | تحميل الصفحة | حفظ | الإجمالي |\n|---|---|---|---|---|---|---|")
    c = httpx.Client(timeout=90)
    working = 0
    for s in STORES:
        t = time.perf_counter()
        try:
            r = c.post(f"{url}/functions/v1/scan", headers={"x-cron-secret": cron_secret}, json={"job": "scan", "store": s})
            total = (time.perf_counter() - t) * 1000
            j = r.json() if r.headers.get("content-type", "").startswith("application/json") else {"error": f"HTTP {r.status_code}"}
        except Exception as e:
            total, j = 0, {"error": str(e)}
        if "skipped" in j:
            OUT.append(f"| {s} | — | اتخطى: {j['skipped']} | | | | |")
        elif "error" in j:
            OUT.append(f"| {s} | ❌ | {str(j['error'])[:80]} | | | | |")
        else:
            working += 1 if j.get("cards") else 0
            if j.get("cards") and not j.get("accepted"):   # page read but no deals parsed → print a sample card for diagnosis
                try:
                    d = c.post(f"{url}/functions/v1/scan", headers={"x-cron-secret": cron_secret}, json={"job": "debug", "store": s}).json()
                    print(f"::group::{s} sample (HTTP {d.get('code')}, {d.get('length')} bytes)\n{d.get('sample', '')}\n::endgroup::")
                except Exception as e:
                    print(f"debug {s} failed: {e}")
            OUT.append(f"| {s} | {j.get('code')} | {j.get('cards')} | {j.get('accepted')} | {j.get('fetch_ms')} ms | {j.get('save_ms')} ms | {total:.0f} ms |")
    OUT.append(f"\nمتاجر شغالة فعلاً من سيرفر Supabase: **{working}/{len(STORES)}**. "
               "أقصى تأخير للاكتشاف ≈ 30 ث (دورة الفحص) + زمن الصفحة؛ صفحة العروض بتتراجع كل ~60 ث.")
    text = "\n".join(OUT)
    print(text)
    out("report", text)
    out("working", str(working))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        open(os.environ["GITHUB_STEP_SUMMARY"], "a").write(text + "\n")


if __name__ == "__main__":
    main()
