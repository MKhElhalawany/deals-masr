import json
import os
import sqlite3
import threading
from contextlib import contextmanager

from . import config

_local = threading.local()

SCHEMA = """
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS marketplaces (
  id TEXT PRIMARY KEY, name_ar TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
  last_run_at INTEGER, last_status TEXT, last_count INTEGER
);

CREATE TABLE IF NOT EXISTS products (
  id TEXT PRIMARY KEY,               -- marketplace:external_id
  marketplace TEXT NOT NULL,
  external_id TEXT NOT NULL,
  canonical_url TEXT NOT NULL,
  title TEXT NOT NULL,
  image_url TEXT,
  url TEXT NOT NULL,                 -- affiliate/deep link
  category TEXT,
  rating REAL,
  rating_count INTEGER,
  original_price REAL NOT NULL,
  current_price REAL NOT NULL,
  discount_pct REAL NOT NULL,
  currency TEXT NOT NULL DEFAULT 'EGP',
  first_seen INTEGER NOT NULL,
  deal_since INTEGER NOT NULL,       -- when current discount started
  last_checked INTEGER NOT NULL,
  last_changed INTEGER NOT NULL,
  active INTEGER NOT NULL DEFAULT 1,
  search_text TEXT NOT NULL DEFAULT ''
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_products_url ON products(canonical_url);
CREATE INDEX IF NOT EXISTS ix_products_active ON products(active, discount_pct);

CREATE TABLE IF NOT EXISTS price_history (
  product_id TEXT NOT NULL, original_price REAL NOT NULL, current_price REAL NOT NULL,
  checked_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_hist ON price_history(product_id, checked_at);

CREATE TABLE IF NOT EXISTS search_cache (
  key TEXT PRIMARY KEY, payload TEXT NOT NULL, created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS notifications (
  id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, product_id TEXT,
  title TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL, sent INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS shein_quotes (
  id TEXT PRIMARY KEY, input_type TEXT NOT NULL, input_value TEXT, image_path TEXT,
  goods_id TEXT, sa_url TEXT, status TEXT NOT NULL,       -- pending | quoted | not_found
  title TEXT, image_url TEXT, original_price_sar REAL, product_url TEXT,
  candidates TEXT, admin_note TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS orders (
  id TEXT PRIMARY KEY, customer_name TEXT NOT NULL, customer_phone TEXT NOT NULL,
  address TEXT, items TEXT NOT NULL, subtotal_egp REAL NOT NULL, delivery_fee_egp REAL NOT NULL,
  total_egp REAL NOT NULL, pricing TEXT NOT NULL, status TEXT NOT NULL,
  created_at INTEGER NOT NULL, confirmed_at INTEGER, updated_at INTEGER NOT NULL
);
"""

DEFAULT_SETTINGS = {
    "tiers": [
        {"key": "deal", "label_ar": "عروض", "min_pct": 10},
        {"key": "hot", "label_ar": "عروض ساخنة 🔥", "min_pct": 30},
        {"key": "super", "label_ar": "سوبر ديلز", "min_pct": 50},
        {"key": "mega", "label_ar": "ميجا ديلز", "min_pct": 70},
    ],
    "min_discount_pct": 10,
    "shein_pricing": {"sar_to_egp": "13.5", "multiplier": "1.20", "delivery_fee_egp": "130"},
    "refresh_minutes": 180,
    "major_deal_pct": 50,
    "expire_after_hours": 48,
    "search_cache_minutes": 30,
    "order_countdown_days": 35,
    "amazon_deal_keywords": ["air fryer", "smart watch", "headphones", "laptop", "perfume", "vacuum cleaner"],
    "whatsapp_number": "",
}

DEFAULT_MARKETPLACES = [("amazon", "أمازون مصر"), ("jumia", "جوميا مصر"), ("noon", "نون مصر")]


def conn() -> sqlite3.Connection:
    c = getattr(_local, "conn", None)
    if c is None:
        os.makedirs(os.path.dirname(config.DB_PATH) or ".", exist_ok=True)
        c = sqlite3.connect(config.DB_PATH, check_same_thread=False)
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA journal_mode=WAL")
        _local.conn = c
    return c


def reset_connection():
    c = getattr(_local, "conn", None)
    if c is not None:
        c.close()
        _local.conn = None


@contextmanager
def tx():
    c = conn()
    try:
        yield c
        c.commit()
    except Exception:
        c.rollback()
        raise


def init():
    with tx() as c:
        c.executescript(SCHEMA)
        for k, v in DEFAULT_SETTINGS.items():
            c.execute("INSERT OR IGNORE INTO settings(key,value) VALUES(?,?)", (k, json.dumps(v, ensure_ascii=False)))
        for mid, name in DEFAULT_MARKETPLACES:
            c.execute("INSERT OR IGNORE INTO marketplaces(id,name_ar) VALUES(?,?)", (mid, name))


def get_setting(key: str):
    row = conn().execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
    return json.loads(row["value"]) if row else DEFAULT_SETTINGS.get(key)


def all_settings() -> dict:
    return {r["key"]: json.loads(r["value"]) for r in conn().execute("SELECT key,value FROM settings")}


def set_setting(key: str, value):
    if key not in DEFAULT_SETTINGS:
        raise KeyError(key)
    with tx() as c:
        c.execute("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)", (key, json.dumps(value, ensure_ascii=False)))
