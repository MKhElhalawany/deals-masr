#!/usr/bin/env python3
"""Measures REAL delivery latency of the live pipeline (no theoretical numbers):

  1. Realtime: server write (PostgREST + server key, exactly like the worker) → change received on a WebSocket
     subscribed as a normal signed-in app user (same protocol the Android app uses). 15 samples.
  2. FCM: time for Google to accept a high-priority push (HTTP v1), if FIREBASE_SERVICE_ACCOUNT is set.
  3. Worker: heartbeat age and per-store status from scraper_status (detection side), if the worker runs.

Env: SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, WORKER_ENV_FILE (file with SUPABASE_SERVICE_KEY, written by
supabase_setup.py, mode 600), optional FIREBASE_SERVICE_ACCOUNT. Secrets are never printed.
Writes a markdown summary to $LATENCY_OUT (default latency.md) and to the job summary.
"""
from __future__ import annotations

import asyncio
import base64
import json
import os
import secrets
import statistics
import sys
import time
from datetime import datetime, timezone

import httpx
import websockets

URL = os.environ["SUPABASE_URL"].rstrip("/")
PUB = os.environ["SUPABASE_PUBLISHABLE_KEY"]
SERVICE = ""
for line in open(os.environ["WORKER_ENV_FILE"]):
    if line.startswith("SUPABASE_SERVICE_KEY="):
        SERVICE = line.split("=", 1)[1].strip()
print(f"::add-mask::{SERVICE}", flush=True)
SAMPLES = int(os.environ.get("LATENCY_SAMPLES", "15"))
out: list[str] = []


def pct(values, p):
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]


def svc_headers():
    return {"apikey": SERVICE, "Authorization": f"Bearer {SERVICE}", "Content-Type": "application/json"}


async def realtime():
    http = httpx.Client(timeout=20)
    email = f"ci-latency-{int(time.time())}-{secrets.token_hex(3)}@example.com"
    r = http.post(f"{URL}/auth/v1/signup", headers={"apikey": PUB}, json={"email": email, "password": secrets.token_urlsafe(16)})
    r.raise_for_status()
    body = r.json()
    jwt = body.get("access_token") or body.get("session", {}).get("access_token")
    uid = (body.get("user") or body).get("id")
    if not jwt:
        raise RuntimeError("signup returned no session (is e-mail autoconfirm on?)")
    deal_id = f"amazon:ci-latency-{secrets.token_hex(4)}"
    row = {"id": deal_id, "store_id": "amazon", "title": "CI latency probe", "url": "https://example.com",
           "original_price": 1000, "current_price": 900, "active": False}
    http.post(f"{URL}/rest/v1/deals", headers=svc_headers() | {"Prefer": "return=minimal"}, json=row).raise_for_status()

    ws_url = URL.replace("https://", "wss://") + f"/realtime/v1/websocket?apikey={PUB}&vsn=1.0.0"
    e2e, after_ack, commit_to_rx = [], [], []
    try:
        async with websockets.connect(ws_url, open_timeout=20) as ws:
            t_conn = time.perf_counter()
            await ws.send(json.dumps({"topic": "realtime:ci-latency", "event": "phx_join", "ref": "1", "payload": {
                "config": {"broadcast": {"self": False}, "presence": {"key": ""},
                           "postgres_changes": [{"event": "UPDATE", "schema": "public", "table": "deals", "filter": f"id=eq.{deal_id}"}]},
                "access_token": jwt}}))
            subscribed = False
            deadline = time.time() + 30
            while not subscribed and time.time() < deadline:
                m = json.loads(await asyncio.wait_for(ws.recv(), 30))
                if m["event"] == "system" and m["payload"].get("status") == "ok":
                    subscribed = True
                elif m["event"] == "phx_reply" and m["payload"].get("status") == "error":
                    raise RuntimeError(f"join refused: {m['payload']}")
            if not subscribed:
                raise RuntimeError("Realtime subscription not confirmed in 30 s")
            join_ms = (time.perf_counter() - t_conn) * 1000
            await asyncio.sleep(1)
            loop = asyncio.get_running_loop()
            for i in range(SAMPLES):
                price = 899 - i
                t0 = time.perf_counter()
                resp = await loop.run_in_executor(None, lambda: http.patch(
                    f"{URL}/rest/v1/deals?id=eq.{deal_id}", headers=svc_headers() | {"Prefer": "return=minimal"},
                    json={"current_price": price, "last_checked": datetime.now(timezone.utc).isoformat()}))
                t_ack = time.perf_counter()
                resp.raise_for_status()
                got = False
                while not got:
                    m = json.loads(await asyncio.wait_for(ws.recv(), 20))
                    if m["event"] != "postgres_changes":
                        continue
                    data = m["payload"]["data"]
                    if float(data["record"]["current_price"]) != price:
                        continue
                    t1 = time.perf_counter()
                    rx_wall = datetime.now(timezone.utc)
                    got = True
                    e2e.append((t1 - t0) * 1000)
                    after_ack.append(max(0.0, (t1 - t_ack) * 1000))
                    ct = data.get("commit_timestamp")
                    if ct:
                        commit = datetime.fromisoformat(ct.replace("Z", "+00:00"))
                        commit_to_rx.append((rx_wall - commit).total_seconds() * 1000)
                await asyncio.sleep(0.7)
    finally:
        http.delete(f"{URL}/rest/v1/deals?id=eq.{deal_id}", headers=svc_headers())
        if uid:
            http.delete(f"{URL}/auth/v1/admin/users/{uid}", headers=svc_headers())

    rtt = []
    for _ in range(5):
        t = time.perf_counter()
        http.get(f"{URL}/auth/v1/health", headers={"apikey": PUB})
        rtt.append((time.perf_counter() - t) * 1000)
    out.append("### ⚡ Realtime (Supabase → التطبيق المفتوح)")
    out.append(f"عينات: {len(e2e)} · الاشتراك اتأكد في {join_ms:.0f} ms")
    out.append("| المقياس | p50 | p95 | أقصى |\n|---|---|---|---|")
    out.append(f"| كتابة السيرفر → وصول التغيير على WebSocket (كامل) | {pct(e2e,50):.0f} ms | {pct(e2e,95):.0f} ms | {max(e2e):.0f} ms |")
    out.append(f"| بعد تأكيد الكتابة → وصول التغيير | {pct(after_ack,50):.0f} ms | {pct(after_ack,95):.0f} ms | {max(after_ack):.0f} ms |")
    if commit_to_rx:
        out.append(f"| commit في قاعدة البيانات → وصول (ساعات مختلفة، تقريبي) | {pct(commit_to_rx,50):.0f} ms | {pct(commit_to_rx,95):.0f} ms | {max(commit_to_rx):.0f} ms |")
    out.append(f"\nزمن رحلة الشبكة من جهاز القياس (GitHub، أمريكا/أوروبا) لـ Supabase (فرانكفورت): ~{statistics.median(rtt):.0f} ms ذهاب وعودة.")
    return pct(e2e, 50)


def fcm():
    raw = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "").strip()
    out.append("\n### 🔔 FCM (إشعار والتطبيق مقفول)")
    if not raw:
        out.append("مش متفعّل لسه (محتاج سر `FIREBASE_SERVICE_ACCOUNT`). مقستش.")
        return
    from google.auth.transport.requests import Request
    from google.oauth2 import service_account
    info = json.loads(raw if raw.startswith("{") else base64.b64decode(raw))
    creds = service_account.Credentials.from_service_account_info(info, scopes=["https://www.googleapis.com/auth/firebase.messaging"])
    creds.refresh(Request())
    http = httpx.Client(timeout=20)
    times = []
    for i in range(8):
        t = time.perf_counter()
        r = http.post(f"https://fcm.googleapis.com/v1/projects/{info['project_id']}/messages:send",
                      headers={"Authorization": f"Bearer {creds.token}"},
                      json={"message": {"topic": "ci_latency", "android": {"priority": "high", "ttl": "60s"},
                                        "data": {"type": "ci", "n": str(i)}}})
        times.append((time.perf_counter() - t) * 1000)
        if r.status_code >= 400:
            out.append(f"❌ FCM رفض الإرسال: {r.status_code} {r.text[:200]}")
            return
    out.append(f"قبول Google للإشعار (HTTP v1): p50 {pct(times,50):.0f} ms · أقصى {max(times):.0f} ms ({len(times)} عينات).")
    out.append("وصوله للموبايل نفسه بيعتمد على Google والشبكة (عادةً أقل من ثانية للأولوية العالية) — ده ما ينفعش يتقاس من غير جهاز حقيقي.")


def worker_status():
    out.append("\n### 🛰️ السيرفر (اكتشاف العروض)")
    rows = httpx.get(f"{URL}/rest/v1/scraper_status?select=store,ok,message,last_run", headers=svc_headers(), timeout=20).json()
    hb = next((r for r in rows if r["store"] == "worker"), None)
    if not hb:
        out.append("السيرفر لسه ما اشتغلش (محتاج Oracle VM) — زمن الاكتشاف ما ينفعش يتقاس قبل ما يشتغل.")
        return
    age = (datetime.now(timezone.utc) - datetime.fromisoformat(hb["last_run"].replace("Z", "+00:00"))).total_seconds()
    out.append(f"آخر نبضة من السيرفر من {age:.0f} ث — {hb['message']}")
    for r in rows:
        if r["store"] != "worker":
            out.append(f"- {r['store']}: {'✓' if r['ok'] else '⚠️'} {r['message']}")


async def main():
    ok = True
    try:
        await realtime()
    except Exception as e:
        ok = False
        out.append(f"### ⚡ Realtime\n❌ فشل القياس: {type(e).__name__}: {e}")
    for fn in (fcm, worker_status):
        try:
            fn()
        except Exception as e:
            out.append(f"❌ {fn.__name__}: {type(e).__name__}: {e}")
    text = "\n".join(out)
    print(text)
    open(os.environ.get("LATENCY_OUT", "latency.md"), "w").write(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        open(os.environ["GITHUB_STEP_SUMMARY"], "a").write(text + "\n")
    sys.exit(0 if ok else 1)


asyncio.run(main())
