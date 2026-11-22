#!/usr/bin/env python3
"""Zero-touch Firebase (push) setup for the Android app.

Input: FIREBASE_SERVICE_ACCOUNT secret (JSON from Firebase console → Project settings → Service accounts →
"Generate new private key"; raw JSON or base64). Optional FIREBASE_ANDROID_CONFIG (google-services.json content)
if the service account isn't allowed to manage apps.

Registers the Android app package (if missing) and outputs its PUBLIC client identifiers (api key, app id,
project id, sender id) for the build. The service-account key itself is never printed and never goes into the APK.
"""
from __future__ import annotations

import base64
import json
import os
import sys
import time

import httpx

PACKAGE = os.environ.get("ANDROID_PACKAGE", "com.dealsmasr.app.debug")
FB = "https://firebase.googleapis.com/v1beta1"


def out(k, v):
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as f:
            f.write(f"{k}={v}\n")


def log(m):
    print(m, flush=True)


def from_google_services(raw: str) -> dict:
    gs = json.loads(raw if raw.strip().startswith("{") else base64.b64decode(raw))
    client = next((c for c in gs["client"] if c["client_info"]["android_client_info"]["package_name"] == PACKAGE), gs["client"][0])
    return {"api_key": client["api_key"][0]["current_key"], "app_id": client["client_info"]["mobilesdk_app_id"],
            "project_id": gs["project_info"]["project_id"], "sender_id": gs["project_info"]["project_number"]}


def via_management(info: dict) -> dict:
    from google.auth.transport.requests import Request
    from google.oauth2 import service_account
    creds = service_account.Credentials.from_service_account_info(info, scopes=["https://www.googleapis.com/auth/cloud-platform"])
    creds.refresh(Request())
    h = {"Authorization": f"Bearer {creds.token}"}
    proj = info["project_id"]
    c = httpx.Client(timeout=30, headers=h)
    r = c.get(f"{FB}/projects/{proj}/androidApps?pageSize=100")
    if r.status_code == 403:
        raise PermissionError("حساب الخدمة مش مسموحله يدير تطبيقات Firebase (403)")
    r.raise_for_status()
    app = next((a for a in r.json().get("apps", []) if a.get("packageName") == PACKAGE), None)
    if not app:
        log(f"Registering Android app {PACKAGE} in Firebase project {proj} …")
        op = c.post(f"{FB}/projects/{proj}/androidApps", json={"packageName": PACKAGE, "displayName": "عروض مصر"})
        op.raise_for_status()
        name = op.json()["name"]
        for _ in range(30):
            st = c.get(f"https://firebase.googleapis.com/v1beta1/{name}").json()
            if st.get("done"):
                if "error" in st:
                    raise RuntimeError(st["error"].get("message"))
                app = st["response"]
                break
            time.sleep(2)
        if not app:
            raise TimeoutError("Firebase app registration timed out")
    cfg = c.get(f"{FB}/{app['name']}/config")
    cfg.raise_for_status()
    return from_google_services(base64.b64decode(cfg.json()["configFileContents"]).decode())


def main():
    raw = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "").strip()
    gs = os.environ.get("FIREBASE_ANDROID_CONFIG", "").strip()
    if not raw and not gs:
        log("::notice::Firebase not configured (no FIREBASE_SERVICE_ACCOUNT secret) — push stays off; Realtime still works.")
        out("enabled", "false")
        return
    try:
        if raw:
            info = json.loads(raw if raw.startswith("{") else base64.b64decode(raw))
            if info.get("type") != "service_account" or "private_key" not in info:
                raise ValueError("FIREBASE_SERVICE_ACCOUNT مش ملف حساب خدمة صحيح (لازم الملف اللي بينزل من 'Generate new private key')")
            print(f"::add-mask::{info['private_key_id']}", flush=True)
        try:
            cfg = via_management(info) if raw else from_google_services(gs)
        except PermissionError:
            if not gs:
                raise
            cfg = from_google_services(gs)
    except Exception as e:
        log(f"::warning::Firebase setup failed: {e}")
        out("enabled", "false")
        out("error", str(e).replace("\n", " ")[:300])
        return
    for k, v in cfg.items():
        out(k, v)
    out("enabled", "true")
    log(f"Firebase ready ✓ project {cfg['project_id']}, app {PACKAGE}")


if __name__ == "__main__":
    main()
    sys.exit(0)
