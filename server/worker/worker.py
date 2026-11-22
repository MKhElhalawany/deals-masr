"""24/7 scraping worker. Runs ONLY on the server (Docker). Loop priorities:
1) SHEIN price requests from users  2) on-demand searches from users  3) rotating deal discovery.
Writes deals / price history / status / user notifications to Supabase."""
from __future__ import annotations

import logging
import os
import signal
import sys
import time
import traceback

from db import DB, now_iso
from fetcher import Fetcher
from parsers import STORES, Blocked, goods_id, goods_ids_on_page, parse_shein

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)
log = logging.getLogger("worker")

STORE_GAP = int(os.environ.get("STORE_INTERVAL_SEC", "20"))   # min seconds between two pages of the same store
MAX_CYCLES = int(os.environ.get("MAX_CYCLES", "0"))             # 0 = forever (tests use a small number)
SHEIN_BASES = ["https://sa.shein.com", "https://ar.shein.com"]
running = True


def stop(*_):
    global running
    running = False


signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)


class Worker:
    def __init__(self):
        self.db = DB()
        self.f = Fetcher()
        self.cfg, self.cfg_at = {}, 0.0
        self.last_hit: dict[str, float] = {}
        self.blocked_until: dict[str, float] = {}
        self.backoff: dict[str, int] = {}
        self.cursor = 0
        self.last_expire = 0.0

    # ───────── helpers
    def config(self):
        if time.time() - self.cfg_at > 300:
            self.cfg, self.cfg_at = self.db.config(), time.time()
        return self.cfg

    def min_pct(self) -> float:
        return float(self.config().get("min_discount_pct", 10))

    def fetch_store(self, store: str, url: str):
        """Returns (items, cards). Plain HTTP first; headless browser when needed."""
        spec = STORES[store]
        wait = STORE_GAP - (time.time() - self.last_hit.get(store, 0))
        if wait > 0:
            time.sleep(wait)
        self.last_hit[store] = time.time()
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
                log.info("%s plain fetch failed: %s", store, e)
        code, html = self.f.rendered(url)
        if code in (403, 429, 503) and "<article" not in html and "/p/" not in html:
            raise Blocked(f"{store} HTTP {code}")
        return spec["parse"](html)

    def on_blocked(self, store: str, why: str):
        n = self.backoff.get(store, 0) + 1
        self.backoff[store] = n
        mins = min(60, 10 * 2 ** (n - 1))
        self.blocked_until[store] = time.time() + mins * 60
        self.db.status(store, False, f"⚠️ المتجر حاجب السيرفر ({why}) — هنحاول تاني بعد {mins} دقيقة")
        log.warning("%s blocked (%s), backing off %d min", store, why, mins)

    def scan(self, store: str, url: str, label: str) -> int:
        try:
            items, cards = self.fetch_store(store, url)
        except Blocked as e:
            self.on_blocked(store, str(e)); return 0
        except Exception as e:
            self.db.status(store, False, f"⚠️ {type(e).__name__}: {e}"[:300]); log.warning("%s error: %s", store, e); return 0
        events = self.db.save_deals(items, self.min_pct())
        self.db.notify(events)
        self.backoff[store] = 0
        self.db.status(store, cards > 0, (f"✓ {len(items)} عرض من {cards} منتج ({label})" if cards
                                          else f"⚠️ الصفحة اتقرت بس مفيهاش منتجات ({label}) — ممكن شكل الموقع اتغير"), len(items))
        log.info("%s %s: %d cards, %d deals, %d events", store, label, cards, len(items), len(events))
        return len(items)

    # ───────── queues
    def shein_queue(self):
        for q in self.db.select("shein_quotes", "select=id,url,kind&status=eq.pending&order=created_at.asc&limit=5"):
            self.db.update("shein_quotes", f"id=eq.{q['id']}", {"status": "running", "updated_at": now_iso()})
            try:
                patch = self.price_bag(q["url"]) if q["kind"] == "bag" else self.price_product(q["url"])
                patch.update(status="priced", error=None)
            except Exception as e:
                patch = {"status": "failed", "error": str(e)[:300]}
            patch["updated_at"] = now_iso()
            self.db.update("shein_quotes", f"id=eq.{q['id']}", patch)
            log.info("shein quote %s → %s", q["id"], patch.get("status"))

    def _gid(self, url: str) -> str:
        gid = goods_id(url)
        if gid:
            return gid
        code, html = self.f.rendered(url, 2500)  # share/short links: follow them
        gid = goods_ids_on_page(html)
        if not gid:
            raise ValueError("مش لاقيين رقم المنتج في اللينك")
        return gid[0]

    def _price_gid(self, gid: str):
        last = None
        for base in SHEIN_BASES:
            url = f"{base}/product-p-{gid}.html?currency=SAR"
            try:
                _, html = self.f.rendered(url, 4000)
                return parse_shein(html, gid, f"{base}/product-p-{gid}.html")
            except Exception as e:
                last = e
        raise ValueError(str(last) if last else "تعذر فتح صفحة المنتج")

    def price_product(self, url: str) -> dict:
        p = self._price_gid(self._gid(url))
        return {"goods_id": p.goods_id, "title": p.title[:300], "image_url": p.image_url, "retail_sar": p.retail_sar}

    def price_bag(self, url: str) -> dict:
        _, html = self.f.rendered(url, 4000)
        items, failed = [], []
        for gid in goods_ids_on_page(html)[:40]:
            try:
                p = self._price_gid(gid)
                items.append({"goods_id": gid, "title": p.title, "image_url": p.image_url, "retail_sar": p.retail_sar, "url": p.url})
            except Exception:
                failed.append(gid)
        if not items:
            raise ValueError("مالقيناش منتجات في الباج")
        return {"items": items, "retail_sar": round(sum(i["retail_sar"] for i in items), 2),
                "error": f"{len(failed)} منتج ما اتسعرش" if failed else None}

    def search_queue(self):
        for r in self.db.select("search_requests", "select=id,query,stores&status=eq.pending&order=created_at.asc&limit=3"):
            self.db.update("search_requests", f"id=eq.{r['id']}", {"status": "running"})
            stores = [s for s in (r["stores"] or self.enabled_stores()) if s in STORES]
            total = sum(self.scan(s, STORES[s]["search"](r["query"]), f"بحث: {r['query']}")
                        for s in stores if self.blocked_until.get(s, 0) < time.time())
            self.db.update("search_requests", f"id=eq.{r['id']}", {"status": "done", "message": f"{total} عرض", "done_at": now_iso()})

    # ───────── discovery
    def enabled_stores(self) -> list[str]:
        return [s for s in self.config().get("scrape_stores", list(STORES)) if s in STORES]

    def targets(self):
        kws = self.config().get("scrape_keywords", [])
        per_store = []
        for s in self.enabled_stores():
            t = [(s, u, "صفحة العروض") for u in STORES[s]["deals"]] + [(s, STORES[s]["search"](k), k) for k in kws]
            per_store.append(t)
        mixed = []
        for i in range(max((len(x) for x in per_store), default=0)):
            for t in per_store:
                if i < len(t):
                    mixed.append(t[i])
        return mixed

    def step(self):
        self.shein_queue()
        self.search_queue()
        ts = [t for t in self.targets() if self.blocked_until.get(t[0], 0) < time.time()]
        if ts:
            store, url, label = ts[self.cursor % len(ts)]
            self.cursor += 1
            self.scan(store, url, label)
        else:
            time.sleep(10)
        if time.time() - self.last_expire > 600:
            self.db.expire_stale()
            self.last_expire = time.time()
        self.db.status("worker", True, f"شغال ✓ — آخر دورة {time.strftime('%H:%M:%S')}")

    def run(self):
        log.info("worker started (store gap %ss, proxy %s)", STORE_GAP, "on" if os.environ.get("PROXY_URL") else "off")
        cycles = 0
        while running and (MAX_CYCLES == 0 or cycles < MAX_CYCLES):
            try:
                self.step()
            except Exception:
                log.error("step failed:\n%s", traceback.format_exc())
                time.sleep(15)
            cycles += 1
        self.f.close()
        log.info("worker stopped")


if __name__ == "__main__":
    Worker().run()
