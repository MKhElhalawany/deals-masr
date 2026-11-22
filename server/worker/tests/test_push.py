from push import Push


def item(pct, id="amazon:1"):
    return {"id": id, "store": "amazon", "title": "Phone", "original_price": 1000, "current_price": 1000 * (1 - pct / 100), "pct": pct}


def test_topic_conditions_and_low_summary():
    p = Push()
    p.enabled = True
    sent = []
    p._send = lambda m: sent.append(m) or True
    n = p.deal_events([dict(item(75), kind="new"), dict(item(35, "amazon:2"), kind="drop"),
                       dict(item(12, "amazon:3"), kind="new")], {"amazon": "أمازون"})
    assert n == 2
    urgent = sent[0]
    assert urgent["condition"] == "'deals_min_10' in topics || 'deals_min_30' in topics || 'deals_min_50' in topics || 'deals_min_70' in topics"
    assert urgent["data"]["level"] == "urgent" and urgent["android"]["priority"] == "high"
    assert sent[1]["data"]["title"].startswith("🔥 نزل تاني")
    assert "'deals_min_50' in topics" not in sent[1]["condition"]
    p._low_flushed = 0
    p.flush_low()
    assert sent[-1]["topic"] == "deals_min_10" and "-12%" in sent[-1]["data"]["body"]


def test_disabled_without_secret(monkeypatch):
    monkeypatch.delenv("FIREBASE_SERVICE_ACCOUNT", raising=False)
    assert Push().enabled is False
