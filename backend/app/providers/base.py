"""Marketplace provider contract.

Each marketplace integration lives in its own module and implements Provider.
Providers return RawProduct records with REAL prices from an authorised source;
they never invent data. A provider that lacks credentials reports
is_configured() == False and the collector skips it.
"""
from __future__ import annotations

import abc
import re
from dataclasses import dataclass
from urllib.parse import urlsplit, urlunsplit


@dataclass
class RawProduct:
    marketplace: str
    external_id: str
    title: str
    url: str
    original_price: float | None
    current_price: float | None
    image_url: str | None = None
    category: str | None = None
    rating: float | None = None
    rating_count: int | None = None
    currency: str = "EGP"


class ProviderError(Exception):
    pass


class Provider(abc.ABC):
    id: str = ""
    #: True when fetch_deals() returns the provider's complete deal set, so items
    #: missing from a run can be expired. False for keyword-sampled sources.
    complete_snapshot: bool = True

    @abc.abstractmethod
    def is_configured(self) -> bool: ...

    def status_note(self) -> str:
        return "configured" if self.is_configured() else "missing credentials"

    @abc.abstractmethod
    def fetch_deals(self, min_discount: float) -> list[RawProduct]: ...

    def live_search(self, query: str, min_discount: float | None, min_price: float | None,
                    max_price: float | None) -> list[RawProduct] | None:
        """Live search against the source. Return None if the provider only supports
        searching its ingested catalogue (the API layer then searches the DB)."""
        return None


_TRACKING = re.compile(r"[?#].*$")


def canonical_url(url: str) -> str:
    """Strip query/fragment and normalise host so duplicates collapse."""
    parts = urlsplit(url.strip())
    host = parts.netloc.lower().removeprefix("www.")
    path = parts.path.rstrip("/")
    m = re.search(r"/(?:dp|gp/product)/([A-Z0-9]{10})", path)
    if "amazon." in host and m:
        path = f"/dp/{m.group(1)}"
    return urlunsplit(("https", host, path, "", ""))
