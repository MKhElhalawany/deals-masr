import json

import httpx

from db import DB
from parsers import Item


def test_ingest_calls_rpc_with_items():
    seen = {}

    def handler(req: httpx.Request):
        seen["path"], seen["body"], seen["auth"] = req.url.path, json.loads(req.content), req.headers["apikey"]
        return httpx.Response(200, json={"accepted": 1, "events": [{"kind": "new", "id": "jumia:1"}], "notifications": []})

    db = DB("https://x.supabase.co", "service")
    db.http = httpx.Client(transport=httpx.MockTransport(handler), headers=db.http.headers)
    res = db.ingest([Item("jumia", "1", "Air Fryer", "https://j/1", None, 1400, 2000)], 10)
    assert seen["path"] == "/rest/v1/rpc/ingest_deals" and seen["auth"] == "service"
    assert seen["body"]["p_min_pct"] == 10 and seen["body"]["p_items"][0]["id"] == "jumia:1"
    assert res["events"][0]["kind"] == "new"
    assert db.ingest([], 10)["accepted"] == 0
