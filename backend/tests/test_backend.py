"""Backend tests. Test fixtures below are test-only data; the app never ships sample products."""
import json
import os
import tempfile
from decimal import Decimal

import pytest

tmp = tempfile.mkdtemp()
os.environ.update(DB_PATH=os.path.join(tmp, "t.db"), UPLOAD_DIR=os.path.join(tmp, "up"),
                  ADMIN_TOKEN="secret-test", DISABLE_SCHEDULER="1")

from fastapi.testclient import TestClient  # noqa: E402

from app import collector, db, pricing  # noqa: E402
from app.main import app  # noqa: E402
from app.providers import set_registry  # noqa: E402
from app.providers.amazon_creators import parse_search_response  # noqa: E402
from app.providers.base import Provider, RawProduct, canonical_url  # noqa: E402
from app.providers.feed import parse_feed, parse_price  # noqa: E402
from app.providers.shein import extract_goods_id, sa_url  # noqa: E402

ADMIN = {"Authorization": "Bearer secret-test"}


class FakeProvider(Provider):
    def __init__(self, mid, items, complete=True):
        self.id, self.items, self.complete_snapshot = mid, items, complete

    def is_configured(self):
        return True

    def fetch_deals(self, min_discount):
        return list(self.items)


def rp(mid, eid, title, orig, cur, **kw):
    return RawProduct(mid, eid, title, f"https://www.{mid}.example/p/{eid}?utm=x", orig, cur, **kw)


@pytest.fixture(autouse=True)
def fresh_db():
    db.reset_connection()
    if os.path.exists(os.environ["DB_PATH"]):
        os.remove(os.environ["DB_PATH"])
    db.init()
    yield


# ------------------------------------------------------------------ pricing
def test_discount_formula():
    assert pricing.discount_percent(1000, 700) == 30.0
    assert pricing.discount_percent(999, 333) == 66.7
    assert pricing.discount_percent(100, 100) is None      # no discount
    assert pricing.discount_percent(100, 120) is None      # price went up
    assert pricing.discount_percent(0, 10) is None
    assert pricing.discount_percent(None, 10) is None
    assert pricing.savings(1000, 649.5) == 350.5


@pytest.mark.parametrize("pct,tier", [(9.9, None), (10, "deal"), (29.9, "deal"), (30, "hot"), (49.9, "hot"),
                                      (50, "super"), (69.9, "super"), (70, "mega"), (92, "mega")])
def test_tiers(pct, tier):
    assert pricing.classify(pct) == tier


def test_custom_tiers():
    tiers = pricing.tiers_from_config([{"key": "a", "min_pct": 5}, {"key": "b", "min_pct": 40}])
    assert pricing.classify(39, tiers) == "a" and pricing.classify(40, tiers) == "b"


def test_shein_example_from_spec():
    cfg = pricing.SheinPricingConfig()
    ob = pricing.price_order([(100, 1)], cfg)
    assert ob.converted_egp == Decimal("1350.00")
    assert ob.extra_egp == Decimal("270.00")
    assert ob.subtotal_egp == Decimal("1620.00")
    assert ob.delivery_fee_egp == Decimal("130")
    assert ob.total_egp == Decimal("1750.00")


def test_shein_delivery_once_per_order():
    cfg = pricing.SheinPricingConfig()
    ob = pricing.price_order([(100, 1), (50, 2), (19.99, 3)], cfg)
    # (100 + 100 + 59.97) * 13.5 * 1.2 = 4211.514 -> lines rounded separately
    assert ob.delivery_fee_egp == Decimal("130")
    assert ob.subtotal_egp == Decimal("1620.00") + Decimal("1620.00") + Decimal("971.51")
    assert ob.total_egp == ob.subtotal_egp + 130
    assert pricing.price_order([], cfg).total_egp == 0


def test_shein_config_change():
    cfg = pricing.SheinPricingConfig.from_dict({"sar_to_egp": "14", "multiplier": "1.1", "delivery_fee_egp": "150"})
    assert pricing.price_order([(10, 1)], cfg).total_egp == Decimal("304.00")


# ------------------------------------------------------------------ providers
def test_feed_formats():
    csv_feed = "id,title,link,image_link,price,sale_price,product_type\n1,قلاية هوائية,https://j/1,https://i/1,\"2,000 EGP\",1400 EGP,Home\n2,No sale,https://j/2,,500,,X\n"
    out = parse_feed("jumia", csv_feed.encode())
    assert out[0].original_price == 2000 and out[0].current_price == 1400 and out[0].category == "Home"
    js = json.dumps({"products": [{"sku": "A", "name": "Phone", "url": "https://n/A", "old_price": 100, "special_price": 60}]})
    assert parse_feed("noon", js.encode())[0].current_price == 60
    xml = ('<rss xmlns:g="http://base.google.com/ns/1.0"><channel><item><g:id>9</g:id><g:title>TV</g:title>'
           '<g:link>https://j/9</g:link><g:price>10000.00 EGP</g:price><g:sale_price>4990.00 EGP</g:sale_price>'
           '</item></channel></rss>')
    x = parse_feed("jumia", xml.encode())[0]
    assert (x.external_id, x.original_price, x.current_price) == ("9", 10000, 4990)
    assert parse_price("١٬٢٥٠ جنيه") == 1250


def test_amazon_parse():
    body = {"searchResult": {"items": [
        {"asin": "B0TEST0001", "detailPageURL": "https://www.amazon.eg/dp/B0TEST0001?tag=x",
         "itemInfo": {"title": {"displayValue": "Air Fryer"}},
         "images": {"primary": {"large": {"url": "https://m.media-amazon.com/x.jpg"}}},
         "offersV2": {"listings": [{"price": {"money": {"amount": 2999, "currency": "EGP"},
                                              "savingBasis": {"money": {"amount": 4999}},
                                              "savings": {"percentage": 40}}}]}},
        {"asin": "B0NOBASIS1", "offersV2": {"listings": [{"price": {"money": {"amount": 10}}}]}},
    ]}}
    out = parse_search_response(body)
    assert len(out) == 1 and out[0].original_price == 4999 and out[0].image_url.endswith("x.jpg")


def test_canonical_url():
    assert canonical_url("https://www.amazon.eg/Some-Name/dp/B0TEST0001/ref=x?tag=a") == "https://amazon.eg/dp/B0TEST0001"
    assert canonical_url("https://www.jumia.com.eg/item-123.html?utm=1#r") == "https://jumia.com.eg/item-123.html"


@pytest.mark.parametrize("url,gid", [
    ("https://ar.shein.com/SHEIN-Dress-p-12345678-cat-1727.html?src=x", "12345678"),
    ("https://m.shein.com/eg/Top-p-87654321.html", "87654321"),
    ("https://shein.com/goods_detail?goods_id=55556666", "55556666"),
    ("https://onelink.shein.com/x?url=https%3A%2F%2Fsa.shein.com%2Fa-p-11223344.html", "11223344"),
    ("https://example.com/nothing", None),
])
def test_shein_goods_id(url, gid):
    assert extract_goods_id(url) == gid


# ------------------------------------------------------------------ collector
def test_collector_validation_dedupe_history_expiry():
    items = [rp("jumia", "1", "قلاية هوائية فيليبس", 2000, 1400, category="Home"),   # 30% hot
             rp("jumia", "1", "dup", 2000, 1400),                                    # duplicate
             rp("jumia", "2", "fake", 100, 100),                                     # no discount
             rp("jumia", "3", "small", 100, 95),                                     # below threshold
             rp("jumia", "4", "usd", 100, 50, currency="USD"),                       # wrong currency
             rp("jumia", "5", "mega", 1000, 250)]                                    # 75% mega -> notification
    p = FakeProvider("jumia", items)
    s = collector.run_provider(p, now=1000)
    assert (s["accepted"], s["new"], s["rejected"]) == (2, 2, 3)
    assert db.conn().execute("SELECT COUNT(*) FROM notifications").fetchone()[0] == 1
    # price change -> history row; product 5 disappears -> expired
    p.items = [rp("jumia", "1", "قلاية هوائية فيليبس", 2000, 1200)]
    s = collector.run_provider(p, now=2000)
    assert s["changed"] == 1 and s["expired"] == 1
    hist = db.conn().execute("SELECT current_price FROM price_history WHERE product_id='jumia:1' ORDER BY checked_at").fetchall()
    assert [h[0] for h in hist] == [1400, 1200]
    assert db.conn().execute("SELECT active FROM products WHERE id='jumia:5'").fetchone()[0] == 0


def test_sampled_provider_expires_by_age():
    p = FakeProvider("amazon", [rp("amazon", "A", "x", 100, 50)], complete=False)
    collector.run_provider(p, now=1000)
    collector.run_provider(FakeProvider("amazon", [], complete=False), now=2000)
    assert db.conn().execute("SELECT active FROM products").fetchone()[0] == 1
    assert collector.expire_stale(now=1000 + 49 * 3600) == 1


# ------------------------------------------------------------------ API
@pytest.fixture
def client():
    set_registry({"amazon": FakeProvider("amazon", [], complete=False),
                  "jumia": FakeProvider("jumia", [rp("jumia", "1", "قلاية هوائية Air Fryer", 2000, 1400, category="Home", rating_count=50),
                                                  rp("jumia", "2", "Smart Watch", 1000, 450, rating_count=500)]),
                  "noon": FakeProvider("noon", [rp("noon", "7", "Air Fryer XL", 3000, 2600),
                                                rp("noon", "8", "TV", 10000, 2900)])})
    with TestClient(app) as c:
        c.post("/api/admin/refresh", headers=ADMIN)
        yield c
    set_registry(None)


def test_api_deals_tiers_and_sort(client):
    allp = client.get("/api/deals").json()
    assert allp["total"] == 4
    assert [i["discount_pct"] for i in allp["items"]] == [71.0, 55.0, 30.0, 13.3]
    assert [i["tier"] for i in allp["items"]] == ["mega", "super", "hot", "deal"]
    hot = client.get("/api/deals?tier=hot").json()
    assert [i["id"] for i in hot["items"]] == ["jumia:1"]
    assert client.get("/api/deals?stores=noon&sort=price_asc").json()["items"][0]["current_price"] == 2600
    assert client.get("/api/deals?sort=popular").json()["items"][0]["id"] == "jumia:2"
    item = allp["items"][0]
    assert item["savings"] == 7100 and item["last_checked"] > 0


def test_api_search(client):
    r = client.get("/api/search", params={"q": "air fryer"}).json()
    assert {i["id"] for i in r["items"]} == {"jumia:1", "noon:7"}
    r = client.get("/api/search", params={"q": "قلايه"}).json()  # normalised ة/ه
    assert [i["id"] for i in r["items"]] == ["jumia:1"]
    assert client.get("/api/search", params={"q": "air fryer", "stores": "noon"}).json()["total"] == 1
    assert client.get("/api/search", params={"q": "air fryer", "min_discount": 20}).json()["total"] == 1
    assert client.get("/api/search", params={"q": "air fryer", "max_price": 2000}).json()["total"] == 1
    assert client.get("/api/search", params={"q": "air", "category": "Home"}).json()["total"] == 1


def test_api_order_pricing_and_idempotency(client):
    body = {"client_order_id": "ord-abc123", "customer_name": "أحمد", "customer_phone": "01012345678",
            "items": [{"title": "Dress", "quantity": 1, "original_price_sar": 100, "size": "M", "color": "أسود"},
                      {"title": "Top", "quantity": 2, "original_price_sar": 50}]}
    o = client.post("/api/orders", json=body).json()
    assert o["subtotal_egp"] == 3240 and o["delivery_fee_egp"] == 130 and o["total_egp"] == 3370
    assert client.post("/api/orders", json=body).json()["created_at"] == o["created_at"]
    st = client.get("/api/orders", params={"ids": "ord-abc123", "phone": "01012345678"}).json()
    assert st[0]["status"] == "submitted" and st[0]["confirmed_at"] is None
    assert client.get("/api/orders", params={"ids": "ord-abc123", "phone": "0100000000"}).json() == []
    r = client.put("/api/admin/orders/ord-abc123", json={"status": "confirmed"}, headers=ADMIN).json()
    assert r["confirmed_at"]
    assert client.get("/api/orders", params={"ids": "ord-abc123", "phone": "01012345678"}).json()[0]["status"] == "confirmed"


def test_api_quote_flow(client):
    q = client.post("/api/shein/quotes", data={"input_type": "url",
                                                "input_value": "https://ar.shein.com/x-p-12345678.html"}).json()
    assert q["status"] == "pending" and q["sa_url"] == sa_url("12345678")
    assert client.post("/api/shein/resolve", json={"input": "https://ar.shein.com/x-p-12345678.html"}).json()["quote"] is None
    # admin must provide a real price before it is "quoted"
    assert client.put(f"/api/admin/quotes/{q['id']}", json={"status": "quoted"}, headers=ADMIN).status_code == 400
    client.put(f"/api/admin/quotes/{q['id']}", json={"status": "quoted", "title": "Dress", "original_price_sar": 89},
               headers=ADMIN)
    assert client.get("/api/shein/quotes", params={"ids": q["id"]}).json()[0]["original_price_sar"] == 89
    assert client.post("/api/shein/resolve", json={"input": "https://sa.shein.com/a-p-12345678.html"}).json()["quote"]["id"] == q["id"]
    # server overrides a tampered client price with the quoted one
    o = client.post("/api/orders", json={"client_order_id": "ord-q00001", "customer_name": "Sara", "customer_phone": "01100000000",
                                         "items": [{"quote_id": q["id"], "title": "Dress", "quantity": 1, "original_price_sar": 1}]}).json()
    assert o["items"][0]["original_price_sar"] == 89 and o["total_egp"] == round(89 * 13.5 * 1.2 + 130, 2)
    # image quote + candidates
    img = client.post("/api/shein/quotes", data={"input_type": "image"}, files={"image": ("a.png", b"\x89PNG..", "image/png")}).json()
    client.put(f"/api/admin/quotes/{img['id']}", headers=ADMIN, json={"status": "needs_choice", "candidates": [
        {"title": "A", "original_price_sar": 40}, {"title": "B", "original_price_sar": 55}]})
    ch = client.post(f"/api/shein/quotes/{img['id']}/choose", json={"index": 1}).json()
    assert ch["status"] == "quoted" and ch["original_price_sar"] == 55


def test_admin_auth_and_settings(client):
    assert client.get("/api/admin/settings").status_code == 401
    assert client.put("/api/admin/settings/shein_pricing", headers=ADMIN,
                      json={"value": {"sar_to_egp": "14", "multiplier": "1.25", "delivery_fee_egp": "150"}}).status_code == 200
    assert client.get("/api/config").json()["shein_pricing"]["sar_to_egp"] == "14"
    assert client.put("/api/admin/settings/shein_pricing", headers=ADMIN,
                      json={"value": {"sar_to_egp": "-1", "multiplier": "1", "delivery_fee_egp": "0"}}).status_code == 400
    assert client.put("/api/admin/settings/nope", headers=ADMIN, json={"value": 1}).status_code == 404
    assert client.get("/admin").status_code == 200
    m = client.get("/api/marketplaces").json()
    assert {x["id"] for x in m} == {"amazon", "jumia", "noon"}
