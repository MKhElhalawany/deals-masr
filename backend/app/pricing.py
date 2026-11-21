"""Pure pricing logic: discount math, deal tiers, SHEIN order pricing.

Kept free of I/O so it can be unit-tested and mirrored 1:1 in the Android app
(android/app/src/main/java/com/dealsmasr/app/core/).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from decimal import ROUND_HALF_UP, Decimal
from typing import Iterable

TWO = Decimal("0.01")


def d(x) -> Decimal:
    return x if isinstance(x, Decimal) else Decimal(str(x))


def discount_percent(original: float, current: float) -> float | None:
    """((original - current) / original) * 100, rounded to 1 decimal.

    Returns None for invalid input (no original price, non-positive prices, or
    current >= original) so callers never show a fabricated discount.
    """
    if original is None or current is None:
        return None
    o, c = d(original), d(current)
    if o <= 0 or c <= 0 or c >= o:
        return None
    pct = (o - c) / o * 100
    return float(pct.quantize(Decimal("0.1"), rounding=ROUND_HALF_UP))


def savings(original: float, current: float) -> float:
    return float((d(original) - d(current)).quantize(TWO, rounding=ROUND_HALF_UP))


@dataclass(frozen=True)
class Tier:
    key: str
    label_ar: str
    min_pct: float  # inclusive


DEFAULT_TIERS: list[Tier] = [
    Tier("deal", "عروض", 10),
    Tier("hot", "عروض ساخنة 🔥", 30),
    Tier("super", "سوبر ديلز", 50),
    Tier("mega", "ميجا ديلز", 70),
]


def tiers_from_config(raw: list[dict] | None) -> list[Tier]:
    if not raw:
        return DEFAULT_TIERS
    tiers = [Tier(t["key"], t.get("label_ar", t["key"]), float(t["min_pct"])) for t in raw]
    return sorted(tiers, key=lambda t: t.min_pct)


def classify(pct: float | None, tiers: list[Tier] = DEFAULT_TIERS) -> str | None:
    """Highest tier whose min_pct <= pct. None if below the lowest tier."""
    if pct is None:
        return None
    match = None
    for t in sorted(tiers, key=lambda t: t.min_pct):
        if pct >= t.min_pct:
            match = t.key
    return match


# ---------------------------------------------------------------- SHEIN pricing
@dataclass(frozen=True)
class SheinPricingConfig:
    sar_to_egp: Decimal = Decimal("13.5")
    multiplier: Decimal = Decimal("1.20")
    delivery_fee_egp: Decimal = Decimal("130")

    @staticmethod
    def from_dict(raw: dict | None) -> "SheinPricingConfig":
        raw = raw or {}
        return SheinPricingConfig(
            d(raw.get("sar_to_egp", "13.5")),
            d(raw.get("multiplier", "1.20")),
            d(raw.get("delivery_fee_egp", "130")),
        )


@dataclass
class LineBreakdown:
    sar_unit: Decimal
    quantity: int
    sar_total: Decimal
    converted_egp: Decimal  # sar_total * rate
    extra_egp: Decimal  # converted * (multiplier - 1)
    line_total_egp: Decimal  # converted * multiplier


@dataclass
class OrderBreakdown:
    lines: list[LineBreakdown] = field(default_factory=list)
    sar_total: Decimal = Decimal(0)
    converted_egp: Decimal = Decimal(0)
    extra_egp: Decimal = Decimal(0)
    subtotal_egp: Decimal = Decimal(0)
    delivery_fee_egp: Decimal = Decimal(0)
    total_egp: Decimal = Decimal(0)


def price_line(sar_unit, quantity: int, cfg: SheinPricingConfig) -> LineBreakdown:
    if quantity < 1:
        raise ValueError("quantity must be >= 1")
    unit = d(sar_unit)
    if unit <= 0:
        raise ValueError("price must be > 0")
    sar_total = unit * quantity
    converted = sar_total * cfg.sar_to_egp
    line_total = converted * cfg.multiplier
    q = lambda x: x.quantize(TWO, rounding=ROUND_HALF_UP)
    return LineBreakdown(unit, quantity, q(sar_total), q(converted), q(line_total - converted), q(line_total))


def price_order(items: Iterable[tuple], cfg: SheinPricingConfig) -> OrderBreakdown:
    """items: iterable of (sar_unit_price, quantity). Delivery fee added ONCE per order."""
    ob = OrderBreakdown()
    for sar, qty in items:
        ln = price_line(sar, qty, cfg)
        ob.lines.append(ln)
        ob.sar_total += ln.sar_total
        ob.converted_egp += ln.converted_egp
        ob.extra_egp += ln.extra_egp
        ob.subtotal_egp += ln.line_total_egp
    ob.delivery_fee_egp = cfg.delivery_fee_egp if ob.lines else Decimal(0)
    ob.total_egp = ob.subtotal_egp + ob.delivery_fee_egp
    return ob
