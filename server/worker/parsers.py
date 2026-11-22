"""Store page parsers (pure functions → unit-testable). Prices are exactly what the page shows; a deal needs
both a crossed-out original price and a lower current price, otherwise it is not a verifiable deal."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from urllib.parse import quote_plus, urljoin

from bs4 import BeautifulSoup, Tag

_AR_DIGITS = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹٬٫", "01234567890123456789,.")
_NUM = re.compile(r"\d[\d,]*(?:\.\d+)?")


def price(text: str | None) -> float | None:
    if not text:
        return None
    m = _NUM.search(text.translate(_AR_DIGITS))
    return float(m.group(0).replace(",", "")) if m else None


@dataclass
class Item:
    store: str
    external_id: str
    title: str
    url: str
    image_url: str | None
    current_price: float
    original_price: float
    rating: float | None = None
    rating_count: int | None = None

    @property
    def id(self) -> str:
        return f"{self.store}:{self.external_id}"

    @property
    def discount_pct(self) -> float | None:
        if self.original_price <= 0 or self.current_price <= 0 or self.current_price >= self.original_price:
            return None
        return round((self.original_price - self.current_price) / self.original_price * 100, 1)


class Blocked(Exception):
    pass


# ───────────────────────────── Amazon.eg
def amazon_search_url(q: str, page: int = 1) -> str:
    return f"https://www.amazon.eg/s?k={quote_plus(q)}&pct-off=10-&language=en_AE" + (f"&page={page}" if page > 1 else "")


AMAZON_DEAL_PAGES = ["https://www.amazon.eg/s?i=specialty-aps&pct-off=30-&s=date-desc-rank&language=en_AE"]


def parse_amazon(html: str, base: str = "https://www.amazon.eg/") -> tuple[list[Item], int]:
    if "validateCaptcha" in html or "Type the characters you see" in html:
        raise Blocked("amazon captcha")
    soup = BeautifulSoup(html, "html.parser")
    cards = soup.select('[data-component-type="s-search-result"]')
    out = []
    for c in cards:
        asin = c.get("data-asin")
        h2 = c.select_one("h2")
        title = ((h2.get("aria-label") or h2.get_text(" ", strip=True)) if h2 else "").strip()
        cur = price(_text(c.select_one(".a-price:not(.a-text-price) .a-offscreen")))
        old = price(_text(c.select_one(".a-price.a-text-price .a-offscreen"))) or \
            price(_text(c.select_one('[data-a-strike="true"] .a-offscreen')))
        if not (asin and title and cur and old):
            continue
        img = c.select_one("img.s-image")
        rating = price(_text(c.select_one("span.a-icon-alt")))
        cnt = next((price(t.get_text()) for t in c.select("a[href*=customerReviews] span, span.s-underline-text") if price(t.get_text())), None)
        out.append(Item("amazon", asin, title, f"https://www.amazon.eg/dp/{asin}", img.get("src") if img else None,
                        cur, old, rating if rating and rating <= 5 else None, int(cnt) if cnt else None))
    if not out and not cards:
        out = generic(html, "amazon", base)
    return out, len(cards)


# ───────────────────────────── Jumia Egypt
def jumia_search_url(q: str, page: int = 1) -> str:
    return f"https://www.jumia.com.eg/catalog/?q={quote_plus(q)}" + (f"&page={page}" if page > 1 else "")


JUMIA_DEAL_PAGES = ["https://www.jumia.com.eg/flash-sales/"]
_SKU = re.compile(r"-(\d{5,})\.html")


def parse_jumia(html: str, base: str = "https://www.jumia.com.eg/") -> tuple[list[Item], int]:
    soup = BeautifulSoup(html, "html.parser")
    title = (soup.title.string or "").lower() if soup.title else ""
    cards = soup.select("article.prd")
    if not cards and ("access denied" in title or "attention required" in title or "just a moment" in title):
        raise Blocked("jumia blocked")
    out = []
    for c in cards:
        a = c.select_one("a.core") or c.select_one("a[href]")
        if not a:
            continue
        url = urljoin(base, a.get("href", "")).split("#")[0]
        name = _text(c.select_one(".name")) or a.get("data-gtm-name") or a.get("data-ga4-item_name") or ""
        cur, old = price(_text(c.select_one(".prc"))), price(_text(c.select_one(".old")))
        if not (name and cur and old):
            continue
        sku = a.get("data-gtm-id") or a.get("data-id") or a.get("data-ga4-item_id") or \
            ((_SKU.search(url) or [None, None])[1]) or url.rsplit("/", 1)[-1].split(".")[0]
        img = c.select_one("img.img") or c.select_one("img")
        image = (img.get("data-src") or img.get("src")) if img else None
        rating = price(_text(c.select_one(".stars._s")))
        rev = c.select_one(".rev")
        cnt = price("".join(t for t in rev.find_all(string=True, recursive=False))) if rev else None
        out.append(Item("jumia", sku, name.strip(), url, image if image and image.startswith("http") else None,
                        cur, old, rating if rating and rating <= 5 else None, int(cnt) if cnt else None))
    if not out:
        out = generic(html, "jumia", base)
    return out, max(len(cards), len(out))


# ───────────────────────────── noon Egypt (client-rendered → generic card detector on the rendered DOM)
def noon_search_url(q: str, page: int = 1) -> str:
    return f"https://www.noon.com/egypt-ar/search/?q={quote_plus(q).replace('+', '%20')}" + (f"&page={page}" if page > 1 else "")


# noon robots.txt disallows search pages → category pages only (temporary set, same as the edge scanner)
NOON_DEAL_PAGES: list[str] = [
    "https://www.noon.com/egypt-en/mobiles/?limit=50",
    "https://www.noon.com/egypt-en/eg-home-appliances/?limit=50",
    "https://www.noon.com/egypt-en/fashion/eg-fashion-men-cat/?limit=50",
    "https://www.noon.com/egypt-en/fashion/eg-fashion-women-cat/?limit=50",
    "https://www.noon.com/egypt-en/home-kitchen/?limit=50",
    "https://www.noon.com/egypt-en/beauty/?limit=50",
]


def _noon_id(url: str) -> str:
    seg = url.split("?")[0].rstrip("/").split("/")
    if "p" in seg:
        i = len(seg) - 1 - seg[::-1].index("p")
        if i > 0:
            return seg[i - 1]
    return _default_id(url)


def parse_noon(html: str, base: str = "https://www.noon.com/") -> tuple[list[Item], int]:
    if "Access Denied" in html[:5000] and "/p/" not in html:
        raise Blocked("noon blocked")
    soup = BeautifulSoup(html, "html.parser")
    cards = len(soup.select("a[href*='/p/']"))
    items = generic(html, "noon", base, _noon_id)
    return items, max(cards, len(items))


# ───────────────────────────── generic deal-card detector (port of the app's former GenericParser)
_STRIKE = re.compile(r"(old|was|strike|before|original|crossed|line-?through|prev|regular|rrp|list-?price)", re.I)
_PRICE_CLS = re.compile(r"(price|prc|amount|now|sell|current|final|sale|value)", re.I)
_CURRENCY = re.compile(r"(EGP|E£|LE|ج\.?\s?م|جنيه)", re.I)


def _cls(t: Tag) -> str:
    return " ".join(t.get("class") or [])


def _is_strike(t: Tag) -> bool:
    if t.name in ("del", "s", "strike") or t.get("data-a-strike") == "true":
        return True
    if "line-through" in (t.get("style") or "").replace(" ", ""):
        return True
    return bool(_STRIKE.search(_cls(t)) or _STRIKE.search(t.get("data-qa") or ""))


def _price_of(t: Tag) -> float | None:
    s = t.get_text(" ", strip=True)
    if len(s) > 40:
        return None
    p = price(s)
    return p if p and p >= 1 else None


def _default_id(url: str) -> str:
    path = url.split("://", 1)[-1].split("/", 1)[-1].split("?")[0].rstrip("/")
    segs = [s for s in path.split("/") if any(ch.isdigit() for ch in s) and len(s) >= 4]
    return segs[-1] if segs else format(abs(hash(path)) % (1 << 32), "x")


def _stable_hash(s: str) -> str:
    h = 0
    for ch in s:
        h = (h * 31 + ord(ch)) & 0xFFFFFFFF
    return format(h, "x")


def generic(html: str, store: str, base: str, id_from_url=_default_id) -> list[Item]:
    soup = BeautifulSoup(html, "html.parser")
    out: dict[str, Item] = {}
    for s in soup.find_all(True):
        if not _is_strike(s) or len(s.find_all(True)) > 3:
            continue
        old = _price_of(s)
        if not old:
            continue
        card = s.parent
        depth = 0
        while card is not None and depth < 10 and not (card.find("a", href=True) and card.find("img")):
            card, depth = card.parent, depth + 1
        if card is None or len(card.get_text(" ", strip=True)) > 1500:
            continue
        a = card if card.name == "a" and card.get("href") else card.find("a", href=True)
        link = urljoin(base, a["href"]) if a else ""
        if not link.startswith("http"):
            continue
        prices = []
        for e in card.find_all(True):
            if e is s or _is_strike(e) or any(_is_strike(p) for p in e.parents if isinstance(p, Tag)) or \
                    any(_is_strike(ch) for ch in e.find_all(True, recursive=False)):
                continue
            if _PRICE_CLS.search(_cls(e)) or _PRICE_CLS.search(e.get("data-qa") or "") or \
                    _CURRENCY.search("".join(e.find_all(string=True, recursive=False))):
                p = _price_of(e)
                if p and old * 0.04 < p < old:
                    prices.append(p)
        if not prices:
            continue
        img = card.find("img")
        image = None
        if img:
            for attr in ("data-src", "src", "data-lazy-src"):
                v = urljoin(base, img.get(attr) or "")
                if v.startswith("http"):
                    image = v; break
        names = [t.get_text(" ", strip=True) for t in card.select("[class*=title], [class*=name], [data-qa*=name], [data-qa*=title], h2, h3, h4")]
        cands = ([max(names, key=len)] if names else []) + [img.get("alt", "") if img else "", (card.find("a", title=True) or {}).get("title", "") if card.find("a", title=True) else ""]
        title = next((c.strip() for c in cands if c and len(c.strip()) >= 4), None)
        if not title:
            continue
        ext = f"{id_from_url(link)}-{_stable_hash(title.lower())}"
        it = Item(store, ext, title, link.split("#")[0], image, min(prices), old)
        out.setdefault(it.id, it)
    return list(out.values())


def _text(t) -> str:
    return t.get_text(" ", strip=True) if t else ""


STORES = {
    "amazon": {"name": "أمازون مصر", "search": amazon_search_url, "parse": parse_amazon, "deals": AMAZON_DEAL_PAGES, "browser": False},
    "jumia": {"name": "جوميا مصر", "search": jumia_search_url, "parse": parse_jumia, "deals": JUMIA_DEAL_PAGES, "browser": False},
    "noon": {"name": "نون مصر", "search": None, "parse": parse_noon, "deals": NOON_DEAL_PAGES, "browser": True},
}


# ───────────────────────────── SHEIN (Saudi storefront, highest retail SAR across sizes)
_RETAIL = re.compile(r'"retail(?:Price|_price)"\s*:\s*\{[^{}]*?"amount"\s*:\s*"?([\d.]+)"?')
_RETAIL_SYM = re.compile(r'"retail(?:Price|_price)"\s*:\s*\{[^{}]*?"amountWithSymbol"\s*:\s*"([^"]*)"')
_GNAME = re.compile(r'"goods_name"\s*:\s*"((?:[^"\\]|\\.)*)"')
_GIMG = re.compile(r'"goods_img"\s*:\s*"([^"]+)"')
_EGP = re.compile(r"(EGP|E£|ج\.?\s?م|جنيه)")
GOODS_ID_PATTERNS = [re.compile(r"-p-(\d{5,})(?:-cat-\d+)?\.html", re.I), re.compile(r"/goods[-_/]?(?:detail/)?(\d{5,})", re.I),
                     re.compile(r"[?&](?:goods_id|goodsId|productId)=(\d{5,})", re.I)]


def goods_id(url: str) -> str | None:
    for p in GOODS_ID_PATTERNS:
        m = p.search(url)
        if m:
            return m.group(1)
    from urllib.parse import parse_qs, unquote, urlsplit
    for k in ("url", "link", "redirect"):
        for v in parse_qs(urlsplit(url).query).get(k, []):
            g = goods_id(unquote(v))
            if g:
                return g
    return None


def goods_ids_on_page(html: str) -> list[str]:
    seen: dict[str, None] = {}
    for m in re.finditer(r"-p-(\d{5,})", html):
        seen.setdefault(m.group(1))
    for m in re.finditer(r'"goods_id"\s*:\s*"?(\d{5,})', html):
        seen.setdefault(m.group(1))
    return list(seen)


def _enclosing(s: str, idx: int, min_len: int) -> tuple[int, int] | None:
    depth, i = 0, idx
    while i >= 0:
        if s[i] == "}":
            depth += 1
        elif s[i] == "{":
            if depth == 0:
                break
            depth -= 1
        i -= 1
    if i < 0:
        return None
    start, depth, j, in_str = i, 0, i, False
    while j < len(s):
        ch = s[j]
        if ch == '"' and s[j - 1] != "\\":
            in_str = not in_str
        elif not in_str:
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    break
        j += 1
    if j >= len(s):
        return None
    if j - start >= min_len or start == 0:
        return start, j
    parent = _enclosing(s, start - 1, min_len)
    if parent is None or parent[1] - parent[0] > 150_000:
        return start, j
    return parent


@dataclass
class SheinProduct:
    goods_id: str
    title: str
    image_url: str | None
    retail_sar: float
    url: str


def parse_shein(html: str, gid: str, url: str) -> SheinProduct:
    prices, title, image, wrong = [], None, None, False
    for m in list(re.finditer(r'"goods_id"\s*:\s*"?%s"?' % re.escape(gid), html))[:40]:
        r = _enclosing(html, m.start(), 1500)
        if not r:
            continue
        block = html[r[0]: r[1] + 1]
        if any(_EGP.search(x) for x in _RETAIL_SYM.findall(block)):
            wrong = True
        prices += [float(x) for x in _RETAIL.findall(block) if float(x) > 0]
        title = title or next((json.loads(f'"{x}"') for x in _GNAME.findall(block)), None)
        image = image or next(iter(_GIMG.findall(block)), None)
    if wrong:
        raise ValueError("الصفحة فتحت بأسعار مصر مش السعودية")
    if not prices:
        soup = BeautifulSoup(html, "html.parser")
        prices = [p for p in (price(_text(t)) for t in soup.select(".product-intro__head-price del, [class*=retail], del")) if p]
        og = soup.select_one("meta[property='og:title']")
        title = title or (og.get("content") if og else None)
    if not prices:
        raise ValueError("مالقيناش السعر الأصلي في الصفحة")
    if image and image.startswith("//"):
        image = "https:" + image
    return SheinProduct(gid, title or f"منتج SHEIN {gid}", image, max(prices), url)
