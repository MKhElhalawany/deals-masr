import pytest

from parsers import Blocked, goods_id, goods_ids_on_page, parse_amazon, parse_jumia, parse_noon, parse_shein, price

JUMIA = """<div><article class="prd _fb col c-prd">
<a class="core" href="/philips-air-fryer-123456789.html" data-gtm-id="PH123AA4XYZNAFAMZ" data-gtm-name="Philips Air Fryer">
<div class="img-c"><img class="img" data-src="https://eg.jumia.is/1.jpg" src="data:image/png;base64,x"></div>
<div class="info"><h3 class="name">Philips Air Fryer 4.1L</h3><div class="prc">EGP 2,999</div>
<div class="s-prc-w"><div class="old">EGP 4,999</div><div class="bdg _dsct _sm">40%</div></div>
<div class="rev"><div class="stars _s">4.6 out of 5</div>(213)</div></div></a></article>
<article class="prd"><a class="core" href="/no-sale-555555.html" data-gtm-id="NOSALE"><h3 class="name">No sale</h3><div class="prc">EGP 500</div></a></article></div>"""

AMAZON = """<div data-asin="B0CQMYP3LF" data-component-type="s-search-result">
<a class="a-link-normal" href="/Air-Fryer/dp/B0CQMYP3LF/ref=x"><img class="s-image" src="https://m.media-amazon.com/images/I/1.jpg"></a>
<h2 aria-label="Tefal Easy Fry Air Fryer 4.2L"><span>Tefal Easy Fry Air Fryer 4.2L</span></h2>
<span class="a-icon-alt">4.5 out of 5 stars</span><span class="s-underline-text">1,234</span>
<span class="a-price"><span class="a-offscreen">EGP 3,199.00</span></span>
<span class="a-price a-text-price" data-a-strike="true"><span class="a-offscreen">EGP 4,599.00</span></span></div>
<div data-asin="B0NOSTRIKE" data-component-type="s-search-result"><h2><span>Plain</span></h2>
<span class="a-price"><span class="a-offscreen">EGP 100.00</span></span></div>"""

NOON = """<div class="grid"><div class="ProductBox_box__1"><a href="/egypt-ar/tefal-air-fryer/N70012345V/p/?o=abc">
<img src="https://f.nooncdn.com/p/1.jpg" alt="Tefal Air Fryer 4.2L Black">
<div data-qa="product-name" class="ProductDetails_name__x">Tefal Air Fryer 4.2L Black</div>
<div class="Price_wrapper"><span class="Price_currency">EGP</span><strong class="Price_amount__a">2,499</strong>
<span class="Price_oldPrice__z">3,999</span></div></a></div>
<div><a href="/egypt-ar/no/N7009/p/"><img src="https://f.nooncdn.com/p/2.jpg" alt="x"><div data-qa="product-name">Plain product here</div>
<strong class="Price_amount__a">100</strong></a></div></div>"""


def test_price():
    assert price("EGP 1,299.00") == 1299
    assert price("١٬٢٥٠٫٥ جنيه") == 1250.5
    assert price("—") is None


def test_jumia():
    items, cards = parse_jumia(JUMIA)
    assert cards == 2 and len(items) == 1
    i = items[0]
    assert (i.id, i.url, i.image_url) == ("jumia:PH123AA4XYZNAFAMZ", "https://www.jumia.com.eg/philips-air-fryer-123456789.html", "https://eg.jumia.is/1.jpg")
    assert (i.current_price, i.original_price, i.discount_pct, i.rating, i.rating_count) == (2999, 4999, 40.0, 4.6, 213)


def test_amazon():
    items, cards = parse_amazon(AMAZON)
    assert cards == 2 and len(items) == 1
    i = items[0]
    assert (i.id, i.url, i.title) == ("amazon:B0CQMYP3LF", "https://www.amazon.eg/dp/B0CQMYP3LF", "Tefal Easy Fry Air Fryer 4.2L")
    assert (i.current_price, i.original_price, i.discount_pct, i.rating, i.rating_count) == (3199, 4599, 30.4, 4.5, 1234)


def test_amazon_captcha():
    with pytest.raises(Blocked):
        parse_amazon('<form action="/errors/validateCaptcha">Type the characters you see</form>')


def test_noon_generic():
    items, _ = parse_noon(NOON)
    assert len(items) == 1
    i = items[0]
    assert i.id.startswith("noon:N70012345V-") and (i.current_price, i.original_price) == (2499, 3999)
    assert i.title == "Tefal Air Fryer 4.2L Black"
    assert i.url == "https://www.noon.com/egypt-ar/tefal-air-fryer/N70012345V/p/?o=abc"


def test_shein_highest_retail_ignores_recommendations():
    pad = "x" * 1600
    html = ('<script>var g={"detail":{"goods_id":"12345678","goods_name":"SHEIN Satin Dress \\u0026 Belt",'
            '"goods_img":"//img.ltwebstatic.com/a.jpg","retailPrice":{"amount":"89.00","amountWithSymbol":"89.00 ر.س"},'
            '"sku_list":[{"retailPrice":{"amount":"89.00"}},{"retailPrice":{"amount":"99.00"}}],"pad":"' + pad + '"},'
            '"recommend":[{"goods_id":"99999999","retailPrice":{"amount":"450.00"}}]};</script>')
    p = parse_shein(html, "12345678", "u")
    assert (p.retail_sar, p.title, p.image_url) == (99.0, "SHEIN Satin Dress & Belt", "https://img.ltwebstatic.com/a.jpg")


def test_shein_rejects_egp():
    html = '{"goods_id":"555666","retailPrice":{"amount":"1200","amountWithSymbol":"EGP1,200"},"p":"' + "y" * 1600 + '"}'
    with pytest.raises(ValueError):
        parse_shein(html, "555666", "u")


def test_goods_ids():
    assert goods_id("https://ar.shein.com/SHEIN-Dress-p-12345678-cat-1727.html?x") == "12345678"
    assert goods_id("https://onelink.shein.com/x?url=https%3A%2F%2Fsa.shein.com%2Fa-p-11223344.html") == "11223344"
    assert goods_id("https://example.com") is None
    assert goods_ids_on_page('<a href="/a-p-111222.html"></a>{"goods_id":"333444"}') == ["111222", "333444"]
