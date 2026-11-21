"""Amazon Egypt via the official Amazon Creators API (successor to PA-API 5).

Requires an approved Amazon Associates Egypt account (affiliate-program.amazon.eg)
and Creators API credentials. Docs:
https://affiliate-program.amazon.com/creatorsapi/docs/en-us/introduction
"""
from __future__ import annotations

import time

import httpx

from .. import config
from .base import Provider, ProviderError, RawProduct

MARKETPLACE = "www.amazon.eg"


class AmazonCreatorsProvider(Provider):
    id = "amazon"
    complete_snapshot = False  # deals are sampled via keyword searches

    def __init__(self, keywords_supplier=lambda: [], client: httpx.Client | None = None):
        self._keywords = keywords_supplier
        self._client = client or httpx.Client(timeout=20)
        self._token: str | None = None
        self._token_exp = 0.0

    def is_configured(self) -> bool:
        return bool(config.AMAZON_CLIENT_ID and config.AMAZON_CLIENT_SECRET and config.AMAZON_PARTNER_TAG)

    def status_note(self) -> str:
        if self.is_configured():
            return "configured"
        return "needs AMAZON_CLIENT_ID / AMAZON_CLIENT_SECRET / AMAZON_PARTNER_TAG (Amazon Associates Egypt)"

    # ------------------------------------------------------------------ http
    def _access_token(self) -> str:
        if self._token and time.time() < self._token_exp - 60:
            return self._token
        r = self._client.post(config.AMAZON_TOKEN_URL, json={
            "grant_type": "client_credentials",
            "client_id": config.AMAZON_CLIENT_ID,
            "client_secret": config.AMAZON_CLIENT_SECRET,
            "scope": "creatorsapi::default",
        })
        if r.status_code != 200:
            raise ProviderError(f"amazon token error {r.status_code}")
        body = r.json()
        self._token = body["access_token"]
        self._token_exp = time.time() + float(body.get("expires_in", 3600))
        return self._token

    def _search(self, keywords: str, min_saving: int | None, min_price=None, max_price=None, page=1) -> list[RawProduct]:
        payload = {
            "partnerTag": config.AMAZON_PARTNER_TAG,
            "marketplace": MARKETPLACE,
            "keywords": keywords,
            "itemCount": 10,
            "itemPage": page,
            "currencyOfPreference": "EGP",
            "languagesOfPreference": ["ar_AE"],
            "resources": ["itemInfo.title", "images.primary.large", "offersV2.listings.price",
                          "browseNodeInfo.browseNodes"],
        }
        if min_saving:
            payload["minSavingPercent"] = int(min_saving)
        # Creators API prices are in the lowest currency unit (piasters) like PA-API.
        if min_price:
            payload["minPrice"] = int(min_price * 100)
        if max_price:
            payload["maxPrice"] = int(max_price * 100)
        r = self._client.post(f"{config.AMAZON_API_BASE}/searchItems", json=payload, headers={
            "Authorization": f"Bearer {self._access_token()}",
            "x-marketplace": MARKETPLACE,
        })
        if r.status_code == 429:
            raise ProviderError("amazon throttled")
        if r.status_code != 200:
            raise ProviderError(f"amazon search error {r.status_code}: {r.text[:200]}")
        return parse_search_response(r.json())

    # ------------------------------------------------------------ interface
    def fetch_deals(self, min_discount: float) -> list[RawProduct]:
        out: list[RawProduct] = []
        for kw in self._keywords():
            out.extend(self._search(kw, int(min_discount)))
            time.sleep(1.1)  # stay under 1 TPS default quota
        return out

    def live_search(self, query, min_discount, min_price, max_price):
        return self._search(query, int(min_discount) if min_discount else None, min_price, max_price)


def _get(obj, *path):
    for p in path:
        if isinstance(obj, list):
            obj = obj[p] if len(obj) > p else None
        elif isinstance(obj, dict):
            obj = obj.get(p)
        if obj is None:
            return None
    return obj


def parse_search_response(body: dict) -> list[RawProduct]:
    items = _get(body, "searchResult", "items") or []
    out = []
    for it in items:
        price = _get(it, "offersV2", "listings", 0, "price") or {}
        current = _get(price, "money", "amount")
        basis = _get(price, "savingBasis", "money", "amount")
        if current is None or basis is None:
            continue  # no verified list price -> no verified discount
        out.append(RawProduct(
            marketplace="amazon",
            external_id=it["asin"],
            title=_get(it, "itemInfo", "title", "displayValue") or it["asin"],
            url=it.get("detailPageURL") or f"https://www.amazon.eg/dp/{it['asin']}",
            original_price=float(basis),
            current_price=float(current),
            image_url=_get(it, "images", "primary", "large", "url") or _get(it, "images", "primary", "medium", "url"),
            category=_get(it, "browseNodeInfo", "browseNodes", 0, "displayName"),
            currency=_get(price, "money", "currency") or "EGP",
        ))
    return out
