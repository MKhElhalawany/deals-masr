"""Affiliate product-feed provider (used for Jumia and Noon).

Affiliate programmes / networks deliver product feeds as CSV, JSON or XML
(Google-Shopping style). This provider downloads the feed URL issued to the
affiliate account and maps common column names. No scraping is performed.
"""
from __future__ import annotations

import csv
import io
import json
import re
import xml.etree.ElementTree as ET

import httpx

from .base import Provider, ProviderError, RawProduct

FIELD_ALIASES = {
    "external_id": ["id", "sku", "product_id", "g:id", "item_id"],
    "title": ["title", "name", "product_name", "g:title"],
    "url": ["link", "url", "deeplink", "affiliate_link", "product_url", "g:link"],
    "image_url": ["image_link", "image", "image_url", "g:image_link"],
    "original_price": ["price", "old_price", "original_price", "regular_price", "g:price", "list_price"],
    "current_price": ["sale_price", "special_price", "current_price", "final_price", "g:sale_price"],
    "category": ["category", "product_type", "google_product_category", "g:product_type"],
    "rating": ["rating", "average_rating"],
    "rating_count": ["rating_count", "reviews_count", "review_count"],
}

_NUM = re.compile(r"[-+]?\d[\d,]*\.?\d*")


def parse_price(v) -> float | None:
    if v is None:
        return None
    if isinstance(v, (int, float)):
        return float(v)
    m = _NUM.search(str(v).replace("٬", ",").translate(str.maketrans("٠١٢٣٤٥٦٧٨٩", "0123456789")))
    return float(m.group(0).replace(",", "")) if m else None


def _pick(row: dict, field: str):
    lower = {k.lower().strip(): v for k, v in row.items() if k}
    for alias in FIELD_ALIASES[field]:
        v = lower.get(alias)
        if v not in (None, ""):
            return v
    return None


def map_row(marketplace: str, row: dict) -> RawProduct | None:
    eid, title, url = _pick(row, "external_id"), _pick(row, "title"), _pick(row, "url")
    if not (eid and title and url):
        return None
    orig, cur = parse_price(_pick(row, "original_price")), parse_price(_pick(row, "current_price"))
    rating = parse_price(_pick(row, "rating"))
    rc = parse_price(_pick(row, "rating_count"))
    return RawProduct(marketplace, str(eid), str(title).strip(), str(url).strip(), orig, cur,
                      _pick(row, "image_url"), _pick(row, "category"), rating, int(rc) if rc else None)


def parse_feed(marketplace: str, content: bytes, content_type: str = "") -> list[RawProduct]:
    text = content.decode("utf-8-sig", errors="replace").lstrip()
    rows: list[dict]
    if text.startswith("[") or text.startswith("{"):
        data = json.loads(text)
        rows = data if isinstance(data, list) else data.get("products") or data.get("items") or []
    elif text.startswith("<"):
        root = ET.fromstring(text)
        rows = []
        for item in root.iter():
            if item.tag.split("}")[-1] in ("item", "entry", "product"):
                row = {}
                for ch in item:
                    tag = ch.tag
                    if "}" in tag:
                        tag = "g:" + tag.split("}")[-1]
                    row[tag] = (ch.text or "").strip()
                rows.append(row)
    else:
        dialect = csv.Sniffer().sniff(text[:4096], delimiters=",;\t|")
        rows = list(csv.DictReader(io.StringIO(text), dialect=dialect))
    return [p for p in (map_row(marketplace, r) for r in rows) if p]


class FeedProvider(Provider):
    complete_snapshot = True

    def __init__(self, marketplace: str, url_supplier, client: httpx.Client | None = None):
        self.id = marketplace
        self._url = url_supplier
        self._client = client or httpx.Client(timeout=60, follow_redirects=True)

    def is_configured(self) -> bool:
        return bool(self._url())

    def status_note(self) -> str:
        return "configured" if self.is_configured() else f"needs {self.id.upper()}_FEED_URL (affiliate product feed)"

    def fetch_deals(self, min_discount: float) -> list[RawProduct]:
        r = self._client.get(self._url())
        if r.status_code != 200:
            raise ProviderError(f"{self.id} feed HTTP {r.status_code}")
        return parse_feed(self.id, r.content, r.headers.get("content-type", ""))
