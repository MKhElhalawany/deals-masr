"""Firebase Cloud Messaging (HTTP v1) from the server. Data-only, high-priority messages so the app builds the
notification itself (importance levels) even when it is closed. Enabled only if FIREBASE_SERVICE_ACCOUNT is set."""
from __future__ import annotations

import base64
import json
import logging
import os
import threading
import time

import httpx

log = logging.getLogger("push")
THRESHOLDS = [10, 30, 50, 70]


def level(pct: float) -> str:
    return "urgent" if pct >= 70 else "super" if pct >= 50 else "hot" if pct >= 30 else "low"


class Push:
    def __init__(self):
        raw = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "").strip()
        self.enabled = bool(raw)
        self._lock = threading.Lock()
        self._low = []              # (title, body) of 10–29% deals, sent as one summary every few minutes
        self._low_flushed = time.time()
        self._sent_hour: list[float] = []
        if not self.enabled:
            return
        info = json.loads(raw if raw.startswith("{") else base64.b64decode(raw))
        from google.oauth2 import service_account
        self.project = info["project_id"]
        self.creds = service_account.Credentials.from_service_account_info(
            info, scopes=["https://www.googleapis.com/auth/firebase.messaging"])
        self.http = httpx.Client(timeout=15)

    def _token(self) -> str:
        from google.auth.transport.requests import Request
        if not self.creds.valid:
            self.creds.refresh(Request())
        return self.creds.token

    def _send(self, message: dict) -> bool:
        if not self.enabled:
            return False
        try:
            r = self.http.post(f"https://fcm.googleapis.com/v1/projects/{self.project}/messages:send",
                               json={"message": message}, headers={"Authorization": f"Bearer {self._token()}"})
            if r.status_code == 404 or "UNREGISTERED" in r.text:
                return False
            if r.status_code >= 400:
                log.warning("FCM %s: %s", r.status_code, r.text[:200])
                return False
            return True
        except Exception as e:
            log.warning("FCM error: %s", e)
            return False

    @staticmethod
    def _android(collapse: str | None = None) -> dict:
        a = {"priority": "high", "ttl": "3600s"}
        if collapse:
            a["collapse_key"] = collapse[:60]
        return a

    # ───────── deals → topics (users subscribe to deals_min_<their threshold>)
    def deal_events(self, events: list[dict], store_names: dict) -> int:
        """events from public.ingest_deals: {kind, id, store, title, current_price, original_price, pct}"""
        sent = 0
        now = time.time()
        with self._lock:
            self._sent_hour = [t for t in self._sent_hour if now - t < 3600]
            for e in sorted(events, key=lambda x: -float(x["pct"])):
                pct = float(e["pct"])
                cur, orig = float(e["current_price"]), float(e["original_price"])
                drop = e["kind"] == "drop"
                store = store_names.get(e["store"], e["store"])
                body = f"{e['title'][:110]}\n{cur:,.0f} ج.م بدل {orig:,.0f}"
                if pct < 30:
                    self._low.append(f"-{int(pct)}% {e['title'][:40]} — {cur:,.0f} ج.م")
                    continue
                lvl = level(pct)
                if lvl == "hot" and len(self._sent_hour) >= 120:   # protect users from a flood of 30–49% pushes
                    continue
                again = "نزل تاني! " if drop else ""
                title = {"urgent": f"🚨 {again}الحق! خصم {int(pct)}% على {store}",
                         "super": f"⚡ {again}سوبر عرض -{int(pct)}% — قبل ما يخلص",
                         "hot": f"🔥 {again}عرض ساخن -{int(pct)}% على {store}"}[lvl]
                cond = " || ".join(f"'deals_min_{t}' in topics" for t in THRESHOLDS if t <= pct)
                ok = self._send({"condition": cond, "android": self._android(e["id"]),
                                 "data": {"type": "deal", "level": lvl, "title": title, "body": body,
                                          "deal_id": e["id"], "pct": str(pct), "price": str(round(cur)),
                                          "sent_at": str(int(time.time() * 1000))}})
                if ok:
                    sent += 1
                    self._sent_hour.append(now)
        return sent

    def flush_low(self, every_sec: int = 300):
        with self._lock:
            if not self._low or time.time() - self._low_flushed < every_sec:
                return
            lines, self._low, self._low_flushed = self._low[-6:], [], time.time()
            n = len(lines)
        self._send({"topic": "deals_min_10", "android": self._android("low_summary"),
                    "data": {"type": "deal", "level": "low", "title": f"خصومات جديدة: {n}+", "body": "\n".join(lines)}})

    # ───────── personal pushes (alerts, favourite drops, orders)
    def to_tokens(self, tokens: list[str], title: str, body: str, route: str | None = None, collapse: str | None = None) -> list[str]:
        """Returns tokens that are no longer valid (to delete)."""
        dead = []
        for t in tokens:
            data = {"type": "personal", "level": "hot", "title": title, "body": body}
            if route:
                data["route"] = route
            if collapse:
                data["tag"] = collapse
            if not self._send({"token": t, "android": self._android(collapse), "data": data}):
                dead.append(t)
        return dead
