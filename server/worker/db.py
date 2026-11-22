"""Supabase access with the service-role key. This key only ever exists on the server (env var), never in the app."""
from __future__ import annotations

import json
import os
import time
from datetime import datetime, timedelta, timezone

import httpx


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


class DB:
    def __init__(self, url: str | None = None, key: str | None = None):
        self.url = (url or os.environ["SUPABASE_URL"]).rstrip("/") + "/rest/v1"
        key = key or os.environ["SUPABASE_SERVICE_KEY"]
        self.http = httpx.Client(timeout=30, headers={"apikey": key, "Authorization": f"Bearer {key}",
                                                      "Content-Type": "application/json"})

    def _req(self, method: str, path: str, **kw):
        for attempt in range(4):
            try:
                r = self.http.request(method, self.url + path, **kw)
                if r.status_code >= 500 and attempt < 3:
                    time.sleep(2 * (attempt + 1)); continue
                if r.status_code >= 400:
                    raise RuntimeError(f"{method} {path.split('?')[0]} → {r.status_code}: {r.text[:300]}")
                return r.json() if r.content and r.headers.get("content-type", "").startswith("application/json") else None
            except httpx.TransportError:
                if attempt == 3:
                    raise
                time.sleep(2 * (attempt + 1))

    def select(self, table: str, query: str):
        return self._req("GET", f"/{table}?{query}") or []

    def insert(self, table: str, rows, upsert: bool = False):
        if not rows:
            return
        prefer = "return=minimal" + (",resolution=merge-duplicates" if upsert else "")
        self._req("POST", f"/{table}", content=json.dumps(rows), headers={"Prefer": prefer})

    def delete(self, table: str, filt: str):
        self._req("DELETE", f"/{table}?{filt}")

    def update(self, table: str, filt: str, patch: dict):
        self._req("PATCH", f"/{table}?{filt}", content=json.dumps(patch), headers={"Prefer": "return=minimal"})

    # ───────── config
    def config(self) -> dict:
        return {r["key"]: r["value"] for r in self.select("app_config", "select=key,value")}

    # ───────── deals: one RPC (public.ingest_deals) does validation, change-only upsert, history, events, notifications
    def ingest(self, items, min_pct: float) -> dict:
        rows = [{"id": it.id, "store": it.store, "title": it.title, "url": it.url, "image_url": it.image_url,
                 "current_price": it.current_price, "original_price": it.original_price,
                 "rating": it.rating, "rating_count": it.rating_count} for it in items]
        if not rows:
            return {"accepted": 0, "events": [], "notifications": []}
        return self._req("POST", "/rpc/ingest_deals", content=json.dumps({"p_items": rows, "p_min_pct": min_pct}),
                         headers={"Prefer": "return=representation"}) or {"accepted": 0, "events": [], "notifications": []}

    def expire_stale(self, hours: int = 36):
        self._req("POST", "/rpc/expire_stale_deals", content=json.dumps({"p_hours": hours}))
