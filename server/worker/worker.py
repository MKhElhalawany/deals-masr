"""24/7 scraping worker — runs ONLY on the server (Docker), never on phones.

Threads:
  • one scan loop per store (parallel): deal pages are revisited every other step, keyword searches in between
  • a queue loop (every ~2 s): SHEIN price requests, on-demand searches, order status pushes
Every new deal / price drop is written to Supabase immediately (→ Realtime to open apps) and pushed via FCM.
"""
from __future__ import annotations

import logging
import os
import signal
import sys
import threading
import time
import traceback
from datetime import datetime, timezone

from db import DB, now_iso
from fetcher import Fetcher
from parsers import STORES, Blocked, goods_id, goods_ids_on_page, parse_shein
from push import Push

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [%(threadName)s] %(message)s", stream=sys.stdout)
log = logging.getLogger("worker")

STORE_GAP = float(os.environ.get("STORE_INTERVAL_SEC", "8"))     # min seconds between two pages of the same store
RUN_SECONDS = float(os.environ.get("RUN_SECONDS", "0"))           # 0 = forever (tests use a small number)
SHEIN_BASES = ["https://sa.shein.com", "https://ar.shein.com"]
STORE_NAMES = {k: v["name"] for k, v in STORES.items()}
stop_event = threading.Event()
signal.signal(signal.SIGTERM, lambda *_: stop_event.set())
signal.signal(signal.SIGINT, lambda *_: stop_event.set())
PUSH = Push()


class Config:
    def __init__(self, db: DB):
        self.db, self.data, self.at, self.lock = db, {}, 0.0, threading.Lock()

    def get(self) -> dict:
        with self.lock:
            if time.time() - self.at > 120:
                try:
                    self.data, self.at = self.db.config(), time.time()
                except Exception as e:
                    log.warning("config refresh failed: %s", e)
            return self.data


def notify_users(db: DB, rows: list[dict]):
    """rows = notifications created by public.ingest_deals (alerts / favourite drops) → personal pushes"""
    if not rows or not PUSH.enabled:
        return
    by_user: dict[str, list[dict]] = {}
    for r in rows:
        by_user.setdefault(r["user_id"], []).append(r)
    users = ",".join(by_user)
    tokens = db.select("device_tokens", f"select=token,user_id&enabled=eq.true&user_id=in.({users})")
    for t in tokens:
        for r in by_user.get(t["user_id"], [])[:3]:
            dead = PUSH.to_tokens([t["token"]], r["title"], r["body"], f"product/{r['deal_id']}" if r.get("deal_id") else None, r.get("deal_id"))
            if dead:
                db.delete("device_tokens", f"token=eq.{dead[0]}")


class StoreLoop(threading.Thread):
    def __init__(self, store: str, cfg: Config):
        super().__init__(name=store, daemon=True)
        self.store, self.cfg = store, cfg
        self.db, self.f = DB(), Fetcher()
        self.backoff = 0
        self.kw_i = 0
        self.step_i = 0
        self.last_visit: dict[str, float] = {}
        self.revisit: dict[str, float] = {}

    def targets(self):
        spec = STORES[self.store]
        kws = self.cfg.get().get("scrape_keywords", [])
        deals = [(u, "صفحة العروض") for u in spec["deals"]]
        self.step_i += 1
        if deals and (self.step_i % 2 == 1 or not kws or spec["search"] is None):   # deal/category pages (only these when no permitted search)
            return deals[(self.step_i // 2) % len(deals)]
        if not kws:
            return None
        kw = kws[self.kw_i % len(kws)]; self.kw_i += 1
        return spec["search"](kw), kw

    def run(self):
        spec = STORES[self.store]
        min_pct = lambda: float(self.cfg.get().get("min_discount_pct", 10))
        while not stop_event.is_set():
            if self.store not in self.cfg.get().get("scrape_stores", list(STORES)):
                stop_event.wait(30); continue
            t = self.targets()
            if not t:
                stop_event.wait(30); continue
            url, label = t
            started = time.time()
            try:
                items, cards = self._fetch(url, spec)
                res = self.db.ingest(items, min_pct())
                events = res["events"]
                saved_at = time.time()
                pushed = PUSH.deal_events(events, STORE_NAMES)
                notify_users(self.db, res["notifications"])
                if url in self.last_visit:
                    self.revisit[label] = started - self.last_visit[url]
                self.last_visit[url] = started
                avg = sum(self.revisit.values()) / len(self.revisit) if self.revisit else 0
                self.db.status(self.store, cards > 0,
                               (f"✓ {len(items)} عرض من {cards} منتج ({label}) · صفحة العروض بتتراجع كل ~{int(self.revisit.get('صفحة العروض', 0))} ث"
                                if cards else f"⚠️ الصفحة اتقرت بس مفيهاش منتجات ({label})"), len(items))
                if events:
                    log.info("%s %s: %d deals, %d events, saved in %.1fs, %d pushed (push %.0f ms after save)",
                             self.store, label, len(items), len(events), saved_at - started, pushed, (time.time() - saved_at) * 1000)
                self.backoff = 0
            except Blocked as e:
                self.backoff += 1
                mins = min(60, 5 * 2 ** (self.backoff - 1))
                self.db.status(self.store, False, f"⚠️ المتجر حاجب السيرفر ({e}) — هنحاول تاني بعد {mins} دقيقة")
                log.warning("%s blocked: %s → %d min", self.store, e, mins)
                stop_event.wait(mins * 60); continue
            except Exception as e:
                self.db.status(self.store, False, f"⚠️ {type(e).__name__}: {e}"[:300])
                log.warning("%s error: %s", self.store, e)
                stop_event.wait(15)
            stop_event.wait(max(0.0, STORE_GAP - (time.time() - started)))
        self.f.close()

    def _fetch(self, url, spec):
        if not spec["browser"]:
            try:
                code, html = self.f.plain(url)
                if code == 200:
                    items, cards = spec["parse"](html)
                    if cards:
                        return items, cards
            except Blocked:
                pass
            except Exception as e:
                log.info("%s plain fetch failed: %s", self.store, e)
        code, html = self.f.rendered(url)
        if code in (403, 429, 503) and "<article" not in html and "/p/" not in html:
            raise Blocked(f"HTTP {code}")
        return spec["parse"](html)


class QueueLoop(threading.Thread):
    """User requests (SHEIN prices, searches) + order pushes. Polled every ~2 s."""

    def __init__(self, cfg: Config):
        super().__init__(name="queues", daemon=True)
        self.cfg, self.db, self.f = cfg, DB(), Fetcher()
        self.last_expire = 0.0
        self.last_beat = 0.0

    def run(self):
        while not stop_event.is_set():
            for job in (self.shein, self.searches, self.housekeeping):   # order pushes: DB trigger → edge function
                try:
                    job()
                except Exception:
                    log.error("%s failed:\n%s", job.__name__, traceback.format_exc())
            stop_event.wait(2)
        self.f.close()

    def housekeeping(self):
        PUSH.flush_low()
        if time.time() - self.last_expire > 600:
            self.db.expire_stale()
            self.last_expire = time.time()
        if time.time() - self.last_beat < 30:
            return
        self.last_beat = time.time()
        self.db.status("worker", True, f"شغال ✓ {datetime.now(timezone.utc).strftime('%H:%M:%S')} UTC · push {'on' if PUSH.enabled else 'off'}")

    # SHEIN
    def shein(self):
        for q in self.db.select("shein_quotes", "select=id,url,kind&status=eq.pending&order=created_at.asc&limit=3"):
            self.db.update("shein_quotes", f"id=eq.{q['id']}", {"status": "running", "updated_at": now_iso()})
            try:
                patch = self.price_bag(q["url"]) if q["kind"] == "bag" else self.price_product(q["url"])
                patch.setdefault("error", None)
                patch["status"] = "priced"
            except Exception as e:
                patch = {"status": "failed", "error": str(e)[:300]}
            patch["updated_at"] = now_iso()
            self.db.update("shein_quotes", f"id=eq.{q['id']}", patch)
            log.info("shein quote %s → %s", q["id"], patch["status"])

    def _gid(self, url: str) -> str:
        gid = goods_id(url)
        if gid:
            return gid
        _, html = self.f.rendered(url, 2500)
        ids = goods_ids_on_page(html)
        if not ids:
            raise ValueError("مش لاقيين رقم المنتج في اللينك")
        return ids[0]

    def _price_gid(self, gid: str):
        last = None
        for base in SHEIN_BASES:
            try:
                _, html = self.f.rendered(f"{base}/product-p-{gid}.html?currency=SAR", 4000)
                return parse_shein(html, gid, f"{base}/product-p-{gid}.html")
            except Exception as e:
                last = e
        raise ValueError(str(last) if last else "تعذر فتح صفحة المنتج")

    def price_product(self, url: str) -> dict:
        p = self._price_gid(self._gid(url))
        return {"goods_id": p.goods_id, "title": p.title[:300], "image_url": p.image_url, "retail_sar": p.retail_sar}

    def price_bag(self, url: str) -> dict:
        _, html = self.f.rendered(url, 4000)
        items, failed = [], 0
        for gid in goods_ids_on_page(html)[:40]:
            try:
                p = self._price_gid(gid)
                items.append({"goods_id": gid, "title": p.title, "image_url": p.image_url, "retail_sar": p.retail_sar, "url": p.url})
            except Exception:
                failed += 1
        if not items:
            raise ValueError("مالقيناش منتجات في الباج")
        return {"items": items, "retail_sar": round(sum(i["retail_sar"] for i in items), 2),
                "error": f"{failed} منتج ما اتسعرش" if failed else None}

    # on-demand searches (each store parsed with a fresh fetch, results land in deals → Realtime)
    def searches(self):
        for r in self.db.select("search_requests", "select=id,query,stores&status=eq.pending&order=created_at.asc&limit=3"):
            self.db.update("search_requests", f"id=eq.{r['id']}", {"status": "running"})
            stores = [s for s in (r["stores"] or self.cfg.get().get("scrape_stores", list(STORES))) if s in STORES and STORES[s]["search"]]
            total = 0
            for s in stores:
                try:
                    items, _ = StoreLoop._fetch(self, STORES[s]["search"](r["query"]), STORES[s])
                    res = self.db.ingest(items, float(self.cfg.get().get("min_discount_pct", 10)))
                    PUSH.deal_events(res["events"], STORE_NAMES)
                    notify_users(self.db, res["notifications"])
                    total += res["accepted"]
                except Exception as e:
                    log.info("search %s on %s failed: %s", r["query"], s, e)
            self.db.update("search_requests", f"id=eq.{r['id']}", {"status": "done", "message": f"{total} عرض", "done_at": now_iso()})

    store = "search"  # used by StoreLoop._fetch logging


def main():
    log.info("worker starting: store gap %.0fs, push %s, proxy %s", STORE_GAP, "on" if PUSH.enabled else "off",
             "on" if os.environ.get("PROXY_URL") else "off")
    cfg = Config(DB())
    threads = [StoreLoop(s, cfg) for s in STORES] + [QueueLoop(cfg)]
    for t in threads:
        t.start()
    started = time.time()
    while not stop_event.is_set():
        if RUN_SECONDS and time.time() - started > RUN_SECONDS:
            stop_event.set()
        stop_event.wait(1)
    deadline = time.time() + 30
    for t in threads:
        t.join(timeout=max(0.1, deadline - time.time()))
    log.info("worker stopped")


if __name__ == "__main__":
    main()
