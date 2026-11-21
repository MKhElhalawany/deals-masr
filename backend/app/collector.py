"""Deal collection: fetch -> validate -> dedupe -> upsert -> history -> expire."""
from __future__ import annotations

import logging
import re
import time

from . import db, pricing
from .providers import registry
from .providers.base import Provider, RawProduct, canonical_url

log = logging.getLogger("collector")

_AR_NORMAL = str.maketrans({"أ": "ا", "إ": "ا", "آ": "ا", "ة": "ه", "ى": "ي", "ـ": ""})


def normalize_text(s: str) -> str:
    s = re.sub(r"[ً-ْ]", "", (s or "").lower())  # strip Arabic diacritics
    return re.sub(r"\s+", " ", s.translate(_AR_NORMAL)).strip()


def validate(p: RawProduct, min_discount: float) -> float | None:
    """Return discount% if the product is a verifiable deal, else None."""
    if not p.url or not p.title or p.currency.upper() != "EGP":
        return None
    pct = pricing.discount_percent(p.original_price, p.current_price)
    if pct is None or pct < min_discount or pct > 95:  # >95% treated as data error
        return None
    return pct


def upsert(c, p: RawProduct, pct: float, now: int) -> str:
    """Returns 'new' | 'changed' | 'same'."""
    pid = f"{p.marketplace}:{p.external_id}"
    curl = canonical_url(p.url)
    row = c.execute("SELECT * FROM products WHERE id=? OR canonical_url=?", (pid, curl)).fetchone()
    search_text = normalize_text(f"{p.title} {p.category or ''}")
    if row is None:
        c.execute(
            """INSERT INTO products(id,marketplace,external_id,canonical_url,title,image_url,url,category,rating,
               rating_count,original_price,current_price,discount_pct,currency,first_seen,deal_since,last_checked,
               last_changed,active,search_text) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?)""",
            (pid, p.marketplace, p.external_id, curl, p.title, p.image_url, p.url, p.category, p.rating,
             p.rating_count, p.original_price, p.current_price, pct, "EGP", now, now, now, now, search_text))
        c.execute("INSERT INTO price_history VALUES(?,?,?,?)", (pid, p.original_price, p.current_price, now))
        return "new"
    pid = row["id"]
    changed = row["current_price"] != p.current_price or row["original_price"] != p.original_price
    reactivated = not row["active"]
    c.execute(
        """UPDATE products SET title=?,image_url=COALESCE(?,image_url),url=?,category=COALESCE(?,category),
           rating=COALESCE(?,rating),rating_count=COALESCE(?,rating_count),original_price=?,current_price=?,
           discount_pct=?,last_checked=?,last_changed=?,deal_since=?,active=1,search_text=? WHERE id=?""",
        (p.title, p.image_url, p.url, p.category, p.rating, p.rating_count, p.original_price, p.current_price,
         pct, now, now if changed else row["last_changed"], now if reactivated else row["deal_since"],
         search_text, pid))
    if changed:
        c.execute("INSERT INTO price_history VALUES(?,?,?,?)", (pid, p.original_price, p.current_price, now))
    return "changed" if changed else ("new" if reactivated else "same")


def run_provider(provider: Provider, now: int | None = None) -> dict:
    now = now or int(time.time())
    min_discount = float(db.get_setting("min_discount_pct"))
    major = float(db.get_setting("major_deal_pct"))
    stats = {"fetched": 0, "accepted": 0, "new": 0, "changed": 0, "expired": 0, "rejected": 0}
    raw = provider.fetch_deals(min_discount)
    stats["fetched"] = len(raw)
    seen: set[str] = set()
    with db.tx() as c:
        for p in raw:
            pct = validate(p, min_discount)
            if pct is None:
                stats["rejected"] += 1
                continue
            key = f"{p.marketplace}:{p.external_id}"
            if key in seen:
                continue
            seen.add(key)
            result = upsert(c, p, pct, now)
            stats["accepted"] += 1
            if result in ("new", "changed"):
                stats[result] += 1
                if result == "new" and pct >= major:
                    c.execute("INSERT INTO notifications(kind,product_id,title,body,created_at) VALUES(?,?,?,?,?)",
                              ("major_deal", key, f"خصم {pct:.0f}% 🔥", p.title[:120], now))
        # A product from a complete feed that is missing or no longer discounted -> expired.
        if provider.complete_snapshot:
            rows = c.execute("SELECT id FROM products WHERE marketplace=? AND active=1", (provider.id,)).fetchall()
            for r in rows:
                if r["id"] not in seen:
                    c.execute("UPDATE products SET active=0, last_checked=? WHERE id=?", (now, r["id"]))
                    stats["expired"] += 1
        c.execute("UPDATE marketplaces SET last_run_at=?, last_status=?, last_count=? WHERE id=?",
                  (now, "ok", stats["accepted"], provider.id))
    return stats


def expire_stale(now: int | None = None) -> int:
    """Sampled sources (Amazon keywords) expire when not re-verified for N hours."""
    now = now or int(time.time())
    hours = float(db.get_setting("expire_after_hours"))
    with db.tx() as c:
        cur = c.execute("UPDATE products SET active=0 WHERE active=1 AND last_checked < ?", (now - hours * 3600,))
        return cur.rowcount


def run_all() -> dict:
    results = {}
    enabled = {r["id"] for r in db.conn().execute("SELECT id FROM marketplaces WHERE enabled=1")}
    for mid, provider in registry().items():
        if mid not in enabled:
            results[mid] = "disabled"
            continue
        if not provider.is_configured():
            results[mid] = provider.status_note()
            continue
        try:
            results[mid] = run_provider(provider)
        except Exception as e:  # one broken provider must not stop the others
            log.exception("provider %s failed", mid)
            with db.tx() as c:
                c.execute("UPDATE marketplaces SET last_run_at=?, last_status=? WHERE id=?",
                          (int(time.time()), f"error: {e}"[:300], mid))
            results[mid] = f"error: {e}"
    results["expired_stale"] = expire_stale()
    return results
