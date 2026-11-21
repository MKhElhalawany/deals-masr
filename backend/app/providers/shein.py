"""SHEIN product identification.

SHEIN offers no public product/price API (its affiliate programme gives links,
its Open API is for sellers). So identification works like this:

1. Parse the goods_id from the link the customer pastes (or follow a share
   short-link redirect to get it).
2. Build the matching SHEIN Saudi Arabia product URL for that goods_id.
3. Price lookup goes through a pluggable SheinPriceSource. The default source
   (ManualQuoteSource) puts the request in the admin queue; the admin opens the
   SA link, confirms it is the same product and enters the original (pre-sale)
   SAR price. The app never prices a product until a real price exists.
"""
from __future__ import annotations

import re
from urllib.parse import parse_qs, urlsplit

import httpx

_PATTERNS = [
    re.compile(r"-p-(\d{5,})(?:-cat-\d+)?\.html", re.I),
    re.compile(r"/goods[-_/]?(?:detail/)?(\d{5,})", re.I),
    re.compile(r"[?&](?:goods_id|goodsId|productId)=(\d{5,})", re.I),
]
SHARE_HOSTS = ("onelink.shein.com", "shein.top", "api-shein.shein.com", "s.shein.com")
SA_BASE = "https://sa.shein.com"


def extract_goods_id(url: str) -> str | None:
    url = (url or "").strip()
    for p in _PATTERNS:
        m = p.search(url)
        if m:
            return m.group(1)
    qs = parse_qs(urlsplit(url).query)
    for key in ("url", "link", "redirect"):
        for v in qs.get(key, []):
            gid = extract_goods_id(v)
            if gid:
                return gid
    return None


def is_shein_url(url: str) -> bool:
    host = urlsplit(url.strip()).netloc.lower()
    return "shein." in host or host in SHARE_HOSTS


def sa_url(goods_id: str) -> str:
    return f"{SA_BASE}/product-p-{goods_id}.html"


def resolve_share_link(url: str, client: httpx.Client | None = None) -> str | None:
    """Follow redirects of a customer-supplied share link to find the goods_id."""
    client = client or httpx.Client(timeout=10, follow_redirects=True)
    try:
        r = client.head(url)
        for hop in [*r.history, r]:
            gid = extract_goods_id(str(hop.url)) or extract_goods_id(hop.headers.get("location", ""))
            if gid:
                return gid
    except httpx.HTTPError:
        return None
    return None


class SheinPriceSource:
    """Plug an authorised SHEIN data source here (e.g. a licensed data provider)."""
    automated = False

    def lookup(self, goods_id: str) -> dict | None:
        """Return {title, image_url, original_price_sar, product_url} or None."""
        return None


class ManualQuoteSource(SheinPriceSource):
    automated = False
