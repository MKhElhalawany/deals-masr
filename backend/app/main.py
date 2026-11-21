"""HTTP API for the Android app + admin panel."""
from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import re
import threading
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, HTMLResponse
from pydantic import BaseModel, Field

from . import collector, config, db, pricing
from .providers import registry
from .providers import shein as shein_mod

log = logging.getLogger("api")


@asynccontextmanager
async def lifespan(_app):
    _startup()
    yield


app = FastAPI(title="Deals Masr API", version="1.0", lifespan=lifespan)
STATIC = Path(__file__).parent / "static"

ORDER_STATUSES = ["submitted", "confirmed", "purchased", "shipped", "delivered", "cancelled"]
SORTS = {
    "discount": "discount_pct DESC, current_price ASC",
    "price_asc": "current_price ASC",
    "price_desc": "current_price DESC",
    "newest": "deal_since DESC",
    "popular": "COALESCE(rating_count,0) DESC, COALESCE(rating,0) DESC, discount_pct DESC",
}


# ------------------------------------------------------------------ lifecycle
def _startup():
    db.init()
    os.makedirs(config.UPLOAD_DIR, exist_ok=True)
    if not config.DISABLE_SCHEDULER:
        threading.Thread(target=_scheduler_loop, daemon=True).start()


def _scheduler_loop():
    while True:
        try:
            log.info("collector run: %s", collector.run_all())
        except Exception:
            log.exception("collector loop error")
        time.sleep(max(15, int(db.get_setting("refresh_minutes"))) * 60)


def now() -> int:
    return int(time.time())


# ------------------------------------------------------------------ helpers
def product_json(r, tiers, names) -> dict:
    return {
        "id": r["id"], "marketplace": r["marketplace"], "marketplace_name": names.get(r["marketplace"], r["marketplace"]),
        "title": r["title"], "image_url": r["image_url"], "url": r["url"], "category": r["category"],
        "rating": r["rating"], "rating_count": r["rating_count"],
        "original_price": r["original_price"], "current_price": r["current_price"],
        "discount_pct": r["discount_pct"], "savings": pricing.savings(r["original_price"], r["current_price"]),
        "tier": pricing.classify(r["discount_pct"], tiers), "currency": r["currency"],
        "first_seen": r["first_seen"], "deal_since": r["deal_since"], "last_checked": r["last_checked"],
        "active": bool(r["active"]),
    }


def _names():
    return {r["id"]: r["name_ar"] for r in db.conn().execute("SELECT id,name_ar FROM marketplaces")}


def _query_products(where: list[str], args: list, sort: str, page: int, page_size: int) -> dict:
    tiers = pricing.tiers_from_config(db.get_setting("tiers"))
    where = ["active=1", *where]
    sql_where = " AND ".join(where)
    c = db.conn()
    total = c.execute(f"SELECT COUNT(*) FROM products WHERE {sql_where}", args).fetchone()[0]
    rows = c.execute(f"SELECT * FROM products WHERE {sql_where} ORDER BY {SORTS.get(sort, SORTS['discount'])} "
                     f"LIMIT ? OFFSET ?", [*args, page_size, (page - 1) * page_size]).fetchall()
    names = _names()
    return {"total": total, "page": page, "page_size": page_size, "items": [product_json(r, tiers, names) for r in rows]}


def _tier_range(tier: str | None):
    if not tier:
        return None
    tiers = pricing.tiers_from_config(db.get_setting("tiers"))
    for i, t in enumerate(tiers):
        if t.key == tier:
            upper = tiers[i + 1].min_pct if i + 1 < len(tiers) else None
            return t.min_pct, upper
    raise HTTPException(400, "unknown tier")


def _stores(stores: str | None) -> list[str]:
    valid = set(registry().keys())
    chosen = [s for s in (stores or "").split(",") if s in valid]
    return chosen or sorted(valid)


# ------------------------------------------------------------------ public API
@app.get("/api/health")
def health():
    return {"ok": True, "time": now()}


@app.get("/api/config")
def public_config():
    s = db.all_settings()
    return {
        "tiers": s["tiers"], "min_discount_pct": s["min_discount_pct"], "shein_pricing": s["shein_pricing"],
        "order_countdown_days": s["order_countdown_days"], "whatsapp_number": s["whatsapp_number"],
        "refresh_minutes": s["refresh_minutes"],
    }


@app.get("/api/marketplaces")
def marketplaces():
    reg = registry()
    out = []
    for r in db.conn().execute("SELECT * FROM marketplaces"):
        p = reg.get(r["id"])
        out.append({"id": r["id"], "name_ar": r["name_ar"], "enabled": bool(r["enabled"]),
                    "configured": bool(p and p.is_configured()), "status": p.status_note() if p else "no provider",
                    "last_run_at": r["last_run_at"], "last_status": r["last_status"], "last_count": r["last_count"]})
    return out


@app.get("/api/deals")
def deals(tier: str | None = None, stores: str | None = None, sort: str = "discount",
          min_discount: float | None = None, page: int = Query(1, ge=1), page_size: int = Query(30, ge=1, le=50)):
    where, args = [], []
    rng = _tier_range(tier)
    if rng:
        where.append("discount_pct >= ?"); args.append(rng[0])
        if rng[1] is not None:
            where.append("discount_pct < ?"); args.append(rng[1])
    if min_discount:
        where.append("discount_pct >= ?"); args.append(min_discount)
    st = _stores(stores)
    where.append(f"marketplace IN ({','.join('?' * len(st))})"); args += st
    return _query_products(where, args, sort, page, page_size)


def _live_search(q: str, st: list[str], min_discount, min_price, max_price):
    """Run live provider search (e.g. Amazon) with caching, upserting verified results."""
    ttl = int(db.get_setting("search_cache_minutes")) * 60
    base_min = float(db.get_setting("min_discount_pct"))
    for mid in st:
        p = registry().get(mid)
        if not p or not p.is_configured():
            continue
        key = hashlib.sha1(json.dumps([mid, collector.normalize_text(q), min_discount, min_price, max_price]).encode()).hexdigest()
        row = db.conn().execute("SELECT created_at FROM search_cache WHERE key=?", (key,)).fetchone()
        if row and now() - row["created_at"] < ttl:
            continue
        try:
            results = p.live_search(q, min_discount, min_price, max_price)
        except Exception as e:
            log.warning("live search %s failed: %s", mid, e)
            continue
        if results is None:
            continue
        with db.tx() as c:
            for rp in results:
                pct = collector.validate(rp, base_min)
                if pct is not None:
                    collector.upsert(c, rp, pct, now())
            c.execute("INSERT OR REPLACE INTO search_cache VALUES(?,?,?)", (key, str(len(results)), now()))


@app.get("/api/search")
def search(q: str = Query(..., min_length=1, max_length=100), stores: str | None = None,
           min_discount: float | None = None, min_price: float | None = None, max_price: float | None = None,
           category: str | None = None, sort: str = "discount",
           page: int = Query(1, ge=1), page_size: int = Query(30, ge=1, le=50)):
    st = _stores(stores)
    _live_search(q, st, min_discount, min_price, max_price)
    where, args = [], []
    for tok in collector.normalize_text(q).split()[:6]:
        where.append("search_text LIKE ?"); args.append(f"%{tok}%")
    where.append(f"marketplace IN ({','.join('?' * len(st))})"); args += st
    if min_discount:
        where.append("discount_pct >= ?"); args.append(min_discount)
    if min_price is not None:
        where.append("current_price >= ?"); args.append(min_price)
    if max_price is not None:
        where.append("current_price <= ?"); args.append(max_price)
    if category:
        where.append("category = ?"); args.append(category)
    res = _query_products(where, args, sort, page, page_size)
    res["categories"] = sorted({i["category"] for i in res["items"] if i["category"]})
    res["stores_unavailable"] = [m for m in st if not registry()[m].is_configured()]
    return res


@app.get("/api/categories")
def categories():
    rows = db.conn().execute("SELECT category, COUNT(*) n FROM products WHERE active=1 AND category IS NOT NULL "
                             "GROUP BY category ORDER BY n DESC LIMIT 100")
    return [{"name": r["category"], "count": r["n"]} for r in rows]


@app.get("/api/products/{pid}")
def product(pid: str):
    r = db.conn().execute("SELECT * FROM products WHERE id=?", (pid,)).fetchone()
    if not r:
        raise HTTPException(404)
    hist = db.conn().execute("SELECT original_price,current_price,checked_at FROM price_history WHERE product_id=? "
                             "ORDER BY checked_at", (pid,)).fetchall()
    out = product_json(r, pricing.tiers_from_config(db.get_setting("tiers")), _names())
    out["history"] = [dict(h) for h in hist]
    return out


@app.get("/api/products")
def products_by_ids(ids: str = Query(..., max_length=4000)):
    """Batch status for favourites (includes inactive/expired)."""
    id_list = [i for i in ids.split(",") if i][:100]
    if not id_list:
        return []
    rows = db.conn().execute(f"SELECT * FROM products WHERE id IN ({','.join('?' * len(id_list))})", id_list)
    tiers, names = pricing.tiers_from_config(db.get_setting("tiers")), _names()
    return [product_json(r, tiers, names) for r in rows]


@app.get("/api/notifications")
def notifications(since: int = 0):
    rows = db.conn().execute("SELECT id,kind,product_id,title,body,created_at FROM notifications WHERE created_at>? "
                             "ORDER BY created_at DESC LIMIT 50", (since,))
    return [dict(r) for r in rows]


# ------------------------------------------------------------------ SHEIN
def quote_json(r) -> dict:
    out = dict(r)
    out.pop("image_path", None)
    out["candidates"] = json.loads(r["candidates"]) if r["candidates"] else []
    out["has_image"] = bool(r["image_path"])
    return out


class ResolveIn(BaseModel):
    input: str = Field(..., max_length=2000)


@app.post("/api/shein/resolve")
def shein_resolve(body: ResolveIn):
    text = body.input.strip()
    gid = shein_mod.extract_goods_id(text)
    if not gid and text.startswith("http") and shein_mod.is_shein_url(text):
        gid = shein_mod.resolve_share_link(text)
    if not gid:
        return {"goods_id": None, "sa_url": None, "quote": None}
    r = db.conn().execute("SELECT * FROM shein_quotes WHERE goods_id=? AND status='quoted' AND updated_at>? "
                          "ORDER BY updated_at DESC LIMIT 1", (gid, now() - 86400)).fetchone()
    return {"goods_id": gid, "sa_url": shein_mod.sa_url(gid), "quote": quote_json(r) if r else None}


@app.post("/api/shein/quotes")
async def shein_create_quote(input_type: str = Form(...), input_value: str = Form("", max_length=2000),
                             image: UploadFile | None = File(None)):
    if input_type not in ("url", "image", "text"):
        raise HTTPException(400, "input_type must be url|image|text")
    if input_type == "image" and image is None:
        raise HTTPException(400, "image required")
    if input_type != "image" and not input_value.strip():
        raise HTTPException(400, "input_value required")
    qid = uuid.uuid4().hex[:12]
    image_path = None
    if image is not None:
        data = await image.read()
        if len(data) > 8 * 1024 * 1024:
            raise HTTPException(413, "image too large")
        ext = (Path(image.filename or "").suffix.lower() or ".jpg")[:5]
        if ext not in (".jpg", ".jpeg", ".png", ".webp"):
            ext = ".jpg"
        image_path = os.path.join(config.UPLOAD_DIR, f"{qid}{ext}")
        with open(image_path, "wb") as f:
            f.write(data)
    gid = shein_mod.extract_goods_id(input_value) if input_type == "url" else None
    t = now()
    with db.tx() as c:
        c.execute("INSERT INTO shein_quotes(id,input_type,input_value,image_path,goods_id,sa_url,status,created_at,"
                  "updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                  (qid, input_type, input_value.strip(), image_path, gid, shein_mod.sa_url(gid) if gid else None,
                   "pending", t, t))
    return quote_json(db.conn().execute("SELECT * FROM shein_quotes WHERE id=?", (qid,)).fetchone())


@app.get("/api/shein/quotes")
def shein_quotes(ids: str = Query(..., max_length=2000)):
    id_list = [i for i in ids.split(",") if i][:50]
    if not id_list:
        return []
    rows = db.conn().execute(f"SELECT * FROM shein_quotes WHERE id IN ({','.join('?' * len(id_list))})", id_list)
    return [quote_json(r) for r in rows]


class ChooseIn(BaseModel):
    index: int = Field(..., ge=0)


@app.post("/api/shein/quotes/{qid}/choose")
def shein_choose(qid: str, body: ChooseIn):
    r = db.conn().execute("SELECT * FROM shein_quotes WHERE id=?", (qid,)).fetchone()
    if not r:
        raise HTTPException(404)
    cands = json.loads(r["candidates"] or "[]")
    if r["status"] != "needs_choice" or body.index >= len(cands):
        raise HTTPException(400, "nothing to choose")
    ch = cands[body.index]
    with db.tx() as c:
        c.execute("UPDATE shein_quotes SET status='quoted',title=?,image_url=?,original_price_sar=?,product_url=?,"
                  "updated_at=? WHERE id=?",
                  (ch.get("title"), ch.get("image_url"), float(ch["original_price_sar"]), ch.get("product_url"), now(), qid))
    return quote_json(db.conn().execute("SELECT * FROM shein_quotes WHERE id=?", (qid,)).fetchone())


class OrderItemIn(BaseModel):
    quote_id: str | None = None
    title: str = Field(..., max_length=300)
    product_url: str | None = Field(None, max_length=2000)
    image_url: str | None = Field(None, max_length=2000)
    size: str | None = Field(None, max_length=40)
    color: str | None = Field(None, max_length=40)
    notes: str | None = Field(None, max_length=500)
    quantity: int = Field(..., ge=1, le=50)
    original_price_sar: float = Field(..., gt=0, lt=100000)


class OrderIn(BaseModel):
    client_order_id: str = Field(..., min_length=6, max_length=40)
    customer_name: str = Field(..., min_length=2, max_length=100)
    customer_phone: str = Field(..., min_length=8, max_length=20)
    address: str | None = Field(None, max_length=500)
    items: list[OrderItemIn] = Field(..., min_length=1, max_length=50)


def _order_json(r) -> dict:
    out = dict(r)
    out["items"] = json.loads(r["items"])
    out["pricing"] = json.loads(r["pricing"])
    return out


@app.post("/api/orders")
def create_order(body: OrderIn):
    if not re.fullmatch(r"\+?[0-9 ]{8,20}", body.customer_phone):
        raise HTTPException(400, "invalid phone")
    existing = db.conn().execute("SELECT * FROM orders WHERE id=?", (body.client_order_id,)).fetchone()
    if existing:  # idempotent retry
        return _order_json(existing)
    items = []
    for it in body.items:
        price = it.original_price_sar
        if it.quote_id:  # server-side price wins when we have a quote
            q = db.conn().execute("SELECT * FROM shein_quotes WHERE id=?", (it.quote_id,)).fetchone()
            if q and q["status"] == "quoted" and q["original_price_sar"]:
                price = q["original_price_sar"]
        items.append({**it.model_dump(), "original_price_sar": price})
    cfg = pricing.SheinPricingConfig.from_dict(db.get_setting("shein_pricing"))
    ob = pricing.price_order([(i["original_price_sar"], i["quantity"]) for i in items], cfg)
    for i, ln in zip(items, ob.lines):
        i["line_total_egp"] = float(ln.line_total_egp)
    t = now()
    pricing_snapshot = {"sar_to_egp": str(cfg.sar_to_egp), "multiplier": str(cfg.multiplier),
                        "delivery_fee_egp": str(cfg.delivery_fee_egp), "sar_total": str(ob.sar_total),
                        "converted_egp": str(ob.converted_egp), "extra_egp": str(ob.extra_egp)}
    with db.tx() as c:
        c.execute("INSERT INTO orders(id,customer_name,customer_phone,address,items,subtotal_egp,delivery_fee_egp,"
                  "total_egp,pricing,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                  (body.client_order_id, body.customer_name, body.customer_phone, body.address,
                   json.dumps(items, ensure_ascii=False), float(ob.subtotal_egp), float(ob.delivery_fee_egp),
                   float(ob.total_egp), json.dumps(pricing_snapshot), "submitted", t, t))
    return _order_json(db.conn().execute("SELECT * FROM orders WHERE id=?", (body.client_order_id,)).fetchone())


@app.get("/api/orders")
def order_status(ids: str, phone: str):
    """Customer status sync. Phone acts as a lightweight ownership check."""
    id_list = [i for i in ids.split(",") if i][:50]
    if not id_list:
        return []
    rows = db.conn().execute(f"SELECT id,status,confirmed_at,updated_at,total_egp FROM orders WHERE customer_phone=? "
                             f"AND id IN ({','.join('?' * len(id_list))})", [phone, *id_list])
    return [dict(r) for r in rows]


# ------------------------------------------------------------------ admin
def require_admin(authorization: str = Header("")):
    if not config.ADMIN_TOKEN:
        raise HTTPException(503, "admin disabled: set ADMIN_TOKEN")
    token = authorization.removeprefix("Bearer ").strip()
    if not hmac.compare_digest(token.encode(), config.ADMIN_TOKEN.encode()):
        raise HTTPException(401, "bad token")


@app.get("/admin", response_class=HTMLResponse)
def admin_page():
    return (STATIC / "admin.html").read_text(encoding="utf-8")


admin = Depends(require_admin)


@app.get("/api/admin/settings", dependencies=[admin])
def admin_settings():
    return db.all_settings()


@app.put("/api/admin/settings/{key}", dependencies=[admin])
def admin_set_setting(key: str, value: dict):
    v = value.get("value")
    if key == "shein_pricing":
        cfg = pricing.SheinPricingConfig.from_dict(v)  # validates numbers
        if cfg.sar_to_egp <= 0 or cfg.multiplier < 1 or cfg.delivery_fee_egp < 0:
            raise HTTPException(400, "invalid pricing")
        v = {k: str(getattr(cfg, k)) for k in ("sar_to_egp", "multiplier", "delivery_fee_egp")}
    if key == "tiers":
        pricing.tiers_from_config(v)
    try:
        db.set_setting(key, v)
    except KeyError:
        raise HTTPException(404, "unknown setting")
    return {"ok": True}


@app.get("/api/admin/marketplaces", dependencies=[admin])
def admin_marketplaces():
    return marketplaces()


@app.put("/api/admin/marketplaces/{mid}", dependencies=[admin])
def admin_toggle_marketplace(mid: str, value: dict):
    with db.tx() as c:
        c.execute("UPDATE marketplaces SET enabled=? WHERE id=?", (1 if value.get("enabled") else 0, mid))
    return {"ok": True}


@app.post("/api/admin/refresh", dependencies=[admin])
def admin_refresh():
    return collector.run_all()


@app.get("/api/admin/deals", dependencies=[admin])
def admin_deals(active: int = 1, page: int = 1):
    rows = db.conn().execute("SELECT * FROM products WHERE active=? ORDER BY last_checked DESC LIMIT 100 OFFSET ?",
                             (active, (page - 1) * 100))
    tiers, names = pricing.tiers_from_config(db.get_setting("tiers")), _names()
    return [product_json(r, tiers, names) for r in rows]


@app.delete("/api/admin/deals/{pid}", dependencies=[admin])
def admin_delete_deal(pid: str):
    with db.tx() as c:
        c.execute("DELETE FROM products WHERE id=?", (pid,))
        c.execute("DELETE FROM price_history WHERE product_id=?", (pid,))
    return {"ok": True}


@app.post("/api/admin/deals/purge-expired", dependencies=[admin])
def admin_purge_expired():
    with db.tx() as c:
        ids = [r[0] for r in c.execute("SELECT id FROM products WHERE active=0")]
        c.executemany("DELETE FROM price_history WHERE product_id=?", [(i,) for i in ids])
        c.execute("DELETE FROM products WHERE active=0")
    return {"deleted": len(ids)}


@app.get("/api/admin/quotes", dependencies=[admin])
def admin_quotes(status: str = "pending"):
    rows = db.conn().execute("SELECT * FROM shein_quotes WHERE status=? ORDER BY created_at DESC LIMIT 200", (status,))
    return [quote_json(r) for r in rows]


@app.get("/api/admin/quotes/{qid}/image", dependencies=[admin])
def admin_quote_image(qid: str):
    r = db.conn().execute("SELECT image_path FROM shein_quotes WHERE id=?", (qid,)).fetchone()
    if not r or not r["image_path"] or not os.path.exists(r["image_path"]):
        raise HTTPException(404)
    return FileResponse(r["image_path"])


class QuoteUpdate(BaseModel):
    status: str
    title: str | None = None
    image_url: str | None = None
    original_price_sar: float | None = Field(None, gt=0)
    product_url: str | None = None
    candidates: list[dict] | None = None
    admin_note: str | None = None


@app.put("/api/admin/quotes/{qid}", dependencies=[admin])
def admin_update_quote(qid: str, body: QuoteUpdate):
    if body.status not in ("pending", "quoted", "needs_choice", "not_found"):
        raise HTTPException(400, "bad status")
    if body.status == "quoted" and not (body.original_price_sar and body.title):
        raise HTTPException(400, "quoted requires title and original_price_sar")
    if body.status == "needs_choice":
        if not body.candidates or any(not c.get("original_price_sar") or not c.get("title") for c in body.candidates):
            raise HTTPException(400, "candidates need title and original_price_sar")
    with db.tx() as c:
        c.execute("UPDATE shein_quotes SET status=?,title=?,image_url=?,original_price_sar=?,product_url=?,candidates=?,"
                  "admin_note=?,updated_at=? WHERE id=?",
                  (body.status, body.title, body.image_url, body.original_price_sar, body.product_url,
                   json.dumps(body.candidates, ensure_ascii=False) if body.candidates else None, body.admin_note,
                   now(), qid))
    return {"ok": True}


@app.get("/api/admin/orders", dependencies=[admin])
def admin_orders(status: str | None = None):
    if status:
        rows = db.conn().execute("SELECT * FROM orders WHERE status=? ORDER BY created_at DESC LIMIT 200", (status,))
    else:
        rows = db.conn().execute("SELECT * FROM orders ORDER BY created_at DESC LIMIT 200")
    return [_order_json(r) for r in rows]


@app.put("/api/admin/orders/{oid}", dependencies=[admin])
def admin_update_order(oid: str, value: dict):
    status = value.get("status")
    if status not in ORDER_STATUSES:
        raise HTTPException(400, "bad status")
    t = now()
    with db.tx() as c:
        r = c.execute("SELECT confirmed_at FROM orders WHERE id=?", (oid,)).fetchone()
        if not r:
            raise HTTPException(404)
        confirmed_at = r["confirmed_at"] or (t if status in ORDER_STATUSES[1:5] else None)
        c.execute("UPDATE orders SET status=?, confirmed_at=?, updated_at=? WHERE id=?", (status, confirmed_at, t, oid))
    return {"ok": True, "confirmed_at": confirmed_at}


@app.get("/api/admin/notifications", dependencies=[admin])
def admin_notifications():
    return [dict(r) for r in db.conn().execute("SELECT * FROM notifications ORDER BY created_at DESC LIMIT 200")]


@app.delete("/api/admin/notifications/{nid}", dependencies=[admin])
def admin_delete_notification(nid: int):
    with db.tx() as c:
        c.execute("DELETE FROM notifications WHERE id=?", (nid,))
    return {"ok": True}
