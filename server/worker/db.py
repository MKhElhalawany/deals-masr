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

    # ───────── deals
    def save_deals(self, items, min_pct: float) -> list[dict]:
        """Validate + upsert + price history. Returns events [{kind:new|drop, deal, old_price}]."""
        good = {}
        for it in items:
            pct = it.discount_pct
            if pct is not None and min_pct <= pct <= 95:
                good[it.id] = it
        if not good:
            return []
        ids = list(good)
        existing = {}
        for i in range(0, len(ids), 80):
            chunk = ",".join('"' + x.replace('"', '') + '"' for x in ids[i:i + 80])
            for r in self.select("deals", f"select=id,current_price,original_price,active&id=in.({chunk})"):
                existing[r["id"]] = r
        ts = now_iso()
        changed, same, history, events = [], [], [], []
        for did, it in good.items():
            row = {"id": did, "store_id": it.store, "title": it.title[:500], "url": it.url, "image_url": it.image_url,
                   "original_price": it.original_price, "current_price": it.current_price,
                   "rating": it.rating, "rating_count": it.rating_count, "active": True, "last_checked": ts}
            old = existing.get(did)
            if old is None or not old["active"] or float(old["current_price"]) != it.current_price \
                    or float(old["original_price"]) != it.original_price:
                row["updated_at"] = ts
                if old is None or not old["active"]:
                    row["deal_since"] = ts
                changed.append(row)
                history.append({"deal_id": did, "current_price": it.current_price, "original_price": it.original_price, "checked_at": ts})
                if old is None or not old["active"]:
                    events.append({"kind": "new", "item": it, "old_price": None})
                elif it.current_price < float(old["current_price"]) - 0.5:
                    events.append({"kind": "drop", "item": it, "old_price": float(old["current_price"])})
            else:
                same.append(row)
        self.insert("deals", changed, upsert=True)
        self.insert("deals", same, upsert=True)
        self.insert("price_history", history, upsert=True)
        return events

    def expire_stale(self, hours: int = 36):
        cutoff = (datetime.now(timezone.utc) - timedelta(hours=hours)).isoformat()
        self.update("deals", f"active=eq.true&last_checked=lt.{cutoff}", {"active": False, "updated_at": now_iso()})

    def status(self, store: str, ok: bool, message: str, seen: int = 0):
        self.insert("scraper_status", [{"store": store, "ok": ok, "message": message[:300], "deals_seen": seen,
                                         "last_run": now_iso()}], upsert=True)

    # ───────── notifications for users (alerts + favourites)
    def notify(self, events: list[dict]) -> list[dict]:
        if not events:
            return []
        alerts = self.select("alerts", "select=user_id,query,min_discount,stores,max_price")
        deal_ids = ",".join('"' + e["item"].id + '"' for e in events)
        favs = self.select("favorites", f"select=user_id,deal_id&deal_id=in.({deal_ids})")
        rows = []
        for e in events:
            it, pct = e["item"], e["item"].discount_pct or 0
            title_l = it.title.lower()
            for a in alerts:
                words = [w for w in a["query"].lower().split() if w]
                if words and all(w in title_l for w in words) and pct >= a["min_discount"] \
                        and (not a["stores"] or it.store in a["stores"]) \
                        and (a["max_price"] is None or it.current_price <= float(a["max_price"])):
                    rows.append({"user_id": a["user_id"], "kind": "alert", "deal_id": it.id, "min_pct": pct,
                                 "title": f"تنبيه: {a['query']} بخصم {int(pct)}%",
                                 "body": f"{it.title[:120]} — {it.current_price:,.0f} ج.م بدل {it.original_price:,.0f}"})
            if e["kind"] == "drop":
                for f in favs:
                    if f["deal_id"] == it.id:
                        rows.append({"user_id": f["user_id"], "kind": "price_drop", "deal_id": it.id, "min_pct": pct,
                                     "title": f"نزل السعر! {it.current_price:,.0f} ج.م",
                                     "body": f"{it.title[:120]} (كان {e['old_price']:,.0f})"})
        self.insert("user_notifications", rows)
        return rows
