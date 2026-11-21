import re, urllib.request, gzip, collections, subprocess, sys
subprocess.run([sys.executable, "-m", "pip", "install", "-q", "beautifulsoup4"], check=True)
from bs4 import BeautifulSoup
UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ar-EG,ar;q=0.9,en;q=0.8",
        "Accept": "text/html,application/xhtml+xml", "Accept-Encoding": "gzip"})
    r = urllib.request.urlopen(req, timeout=30); raw = r.read()
    if r.headers.get("Content-Encoding") == "gzip": raw = gzip.decompress(raw)
    return raw.decode("utf-8", "replace")
def cls(t): return ".".join(t.get("class", []))[:80]

print("#### AMAZON")
for attempt in range(3):
    try:
        h = get("https://www.amazon.eg/s?k=air+fryer&pct-off=30-"); break
    except Exception as e: print("err", e); h = None
if h:
    s = BeautifulSoup(h, "html.parser")
    cards = s.select('[data-component-type="s-search-result"]')
    print("cards", len(cards))
    for c in cards[:4]:
        print("--- asin", c.get("data-asin"))
        t = c.select_one("h2"); print(" h2:", t.get_text(" ", strip=True)[:120] if t else None, "| h2 aria:", t.get("aria-label") if t else None)
        for o in c.select(".a-offscreen"): print("  offscreen:", repr(o.get_text(strip=True)), "parent:", cls(o.parent), "gp:", cls(o.parent.parent))
        img = c.select_one("img.s-image"); print(" img:", img.get("src") if img else None)
        a = c.select_one("a.a-link-normal[href*='/dp/']"); print(" href:", a.get("href")[:80] if a else None)
        for r in c.select("[aria-label*='5'], .a-icon-alt"): print("  rating?:", r.get("aria-label") or r.get_text(strip=True)); break
        for b in c.select("span"):
            tx = b.get_text(strip=True)
            if "%" in tx and len(tx) < 40: print("  pct span:", repr(tx), cls(b)); break

print("#### NOON")
h = get("https://www.noon.com/egypt-ar/search/?q=air%20fryer")
s = BeautifulSoup(h, "html.parser")
print(collections.Counter(t.get("data-qa") for t in s.select("[data-qa]")).most_common(25))
cards = s.select('[data-qa^="product-"]') or s.select('[data-qa*="product"]')
print("cards", len(cards))
for c in cards[:3]:
    print("--- card tag", c.name, cls(c), c.get("data-qa"))
    a = c if c.name == "a" else c.select_one("a[href]"); print(" href:", a.get("href") if a else None)
    for t in c.find_all(True):
        tx = t.get_text(" ", strip=True)
        if t.find(True) is None and tx: print("   leaf", t.name, cls(t)[:60], t.get("data-qa"), "|", tx[:90])
    img = c.select_one("img"); print(" img:", img.get("src") if img else None)
nd = s.select_one("script#__NEXT_DATA__"); print("next_data:", bool(nd))

print("#### JUMIA variants")
for u in ["https://www.jumia.com.eg/catalog/?q=air+fryer", "https://www.jumia.com.ng/catalog/?q=air+fryer",
          "https://www.jumia.co.ke/catalog/?q=air+fryer", "https://www.jumia.ma/catalog/?q=air+fryer",
          "https://www.jumia.com.eg/ar/catalog/?q=air+fryer"]:
    try:
        h = get(u); s = BeautifulSoup(h, "html.parser")
        cards = s.select("article.prd")
        print(u, "OK cards", len(cards))
        for c in cards[:3]:
            print("  ---", cls(c))
            for t in c.find_all(True):
                tx = t.get_text(" ", strip=True)
                if t.find(True) is None and tx: print("   leaf", t.name, cls(t), "|", tx[:80])
            a = c.select_one("a.core"); print("  a.core:", a.get("href") if a else None, {k: v for k, v in (a.attrs.items() if a else []) if k.startswith("data-")})
            img = c.select_one("img"); print("  img:", img.get("data-src") or img.get("src") if img else None)
        print("  pagination/sort links:", [x.get("href") for x in s.select("a[href*='sort='], a[href*='discount']")][:8])
    except Exception as e:
        print(u, "ERR", e)
