from .. import config, db
from .amazon_creators import AmazonCreatorsProvider
from .base import Provider
from .feed import FeedProvider

_registry: dict[str, Provider] | None = None


def registry() -> dict[str, Provider]:
    """marketplace id -> provider. Add or replace a marketplace here only."""
    global _registry
    if _registry is None:
        _registry = {
            "amazon": AmazonCreatorsProvider(lambda: db.get_setting("amazon_deal_keywords") or []),
            "jumia": FeedProvider("jumia", lambda: config.JUMIA_FEED_URL),
            "noon": FeedProvider("noon", lambda: config.NOON_FEED_URL),
        }
    return _registry


def set_registry(reg: dict[str, Provider] | None):
    global _registry
    _registry = reg
