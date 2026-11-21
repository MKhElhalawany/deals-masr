"""Dumps the structure of Jumia/Amazon listing pages so scraper selectors can be written against real HTML."""
import re, sys, urllib.request, gzip
from html.parser import HTMLParser

UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
URLS = [
    "https://www.jumia.com.eg/catalog/?q=air+fryer",
    "https://www.jumia.com.eg/catalog/?q=air+fryer&price_discount=30-100",
    "https://www.jumia.com.eg/flash-sales/",
    "https://www.amazon.eg/s?k=air+fryer&language=ar_AE",
    "https://www.amazon.eg/s?k=air+fryer&pct-off=30-",
    "https://www.noon.com/egypt-ar/search/?q=air%20fryer",
]
for url in URLS:
    print("=" * 100); print(url)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ar-EG,ar;q=0.9,en;q=0.8",
                                                   "Accept": "text/html", "Accept-Encoding": "gzip"})
        r = urllib.request.urlopen(req, timeout=30)
        raw = r.read()
        if r.headers.get("Content-Encoding") == "gzip":
            raw = gzip.decompress(raw)
        html = raw.decode("utf-8", "replace")
        print("status", r.status, "len", len(html), "final", r.url)
    except Exception as e:
        print("ERROR", e); continue
    if "captcha" in html.lower():
        print("!! CAPTCHA detected")
    for pat in ["article class=\"prd", "class=\"prc\"", "class=\"old\"", "_dsct", "data-component-type=\"s-search-result\"",
                "a-offscreen", "a-text-price", "productContainer", "__NEXT_DATA__", "ProductBoxLinkHandler", "data-qa=\"product"]:
        print(f"  {pat!r}: {html.count(pat)}")
    # print first product card snippet
    for marker in ["<article class=\"prd", "data-component-type=\"s-search-result\"", "productContainer"]:
        i = html.find(marker)
        if i >= 0:
            snippet = re.sub(r"\s+", " ", html[max(0, i - 50): i + 3500])
            snippet = re.sub(r"<svg.*?</svg>", "<svg/>", snippet)
            print("  --- first card around", marker); print(snippet[:3500]); break
