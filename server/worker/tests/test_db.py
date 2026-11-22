import json
from urllib.parse import parse_qs, unquote, urlsplit

import httpx

from db import DB
from parsers import Item


class FakeREST:
    """Tiny in-memory PostgREST: enough for deals / price_history / alerts / favorites / user_notifications."""
    def __init__(self):
        self.t = {"deals": {}, "price_history": [], "alerts": [], "favorites": [], "user_notifications": []}

    def __call__(self, req: httpx.Request):
        u = urlsplit(str(req.url)); table = u.path.rsplit("/", 1)[-1]; q = unquote(u.query)
        if req.method == "GET":
            rows = list(self.t[table].values()) if table == "deals" else self.t[table]
            if "id=in.(" in q:
                ids = [x.strip('"') for x in q.split("id=in.(")[1].split(")")[0].split(",")]
                key = "deal_id" if table == "favorites" else "id"
                rows = [r for r in rows if r[key] in ids]
            return httpx.Response(200, json=rows)
        body = json.loads(req.content)
        if table == "deals":
            for r in body:
                self.t["deals"].setdefault(r["id"], {}).update(r)
        else:
            self.t[table].extend(body)
        return httpx.Response(201)


def make():
    fake = FakeREST()
    db = DB("https://x.supabase.co", "service")
    db.http = httpx.Client(transport=httpx.MockTransport(fake), headers=db.http.headers)
    return db, fake


def it(eid, cur, old, title="Philips Air Fryer XL"):
    return Item("jumia", eid, title, f"https://j/{eid}", None, cur, old)


def test_events_history_and_notifications():
    db, fake = make()
    fake.t["alerts"] = [{"user_id": "u1", "query": "air fryer", "min_discount": 30, "stores": None, "max_price": None},
                        {"user_id": "u2", "query": "laptop", "min_discount": 10, "stores": None, "max_price": None}]
    ev = db.save_deals([it("1", 1400, 2000), it("2", 95, 100), it("3", 100, 100)], min_pct=10)
    assert [e["kind"] for e in ev] == ["new"]                      # 5% and 0% rejected
    assert set(fake.t["deals"]) == {"jumia:1"} and len(fake.t["price_history"]) == 1
    db.notify(ev)
    assert [n["user_id"] for n in fake.t["user_notifications"]] == ["u1"]   # air fryer alert matched, laptop not
    # unchanged price → no event, no history
    fake.t["deals"]["jumia:1"]["active"] = True
    assert db.save_deals([it("1", 1400, 2000)], 10) == [] and len(fake.t["price_history"]) == 1
    # price drop on a favourite → drop event + favourite notification
    fake.t["favorites"] = [{"user_id": "u3", "deal_id": "jumia:1"}]
    ev = db.save_deals([it("1", 1200, 2000)], 10)
    assert ev[0]["kind"] == "drop" and ev[0]["old_price"] == 1400
    db.notify(ev)
    assert {n["user_id"] for n in fake.t["user_notifications"]} == {"u1", "u3"}
    assert len(fake.t["price_history"]) == 2
