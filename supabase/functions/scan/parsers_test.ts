function assertEquals(a: unknown, b: unknown) { const x = JSON.stringify(a), y = JSON.stringify(b); if (x !== y) throw new Error(`${x} !== ${y}`); }
function assertThrows(f: () => unknown, cls: any) { try { f(); } catch (e) { if (e instanceof cls) return; throw e; } throw new Error("did not throw"); }
import { Blocked, parseAmazon, parseJumia, parseNoon, price } from "./parsers.ts";
const JUMIA = "<div><article class=\"prd _fb col c-prd\">\n<a class=\"core\" href=\"/philips-air-fryer-123456789.html\" data-gtm-id=\"PH123AA4XYZNAFAMZ\" data-gtm-name=\"Philips Air Fryer\">\n<div class=\"img-c\"><img class=\"img\" data-src=\"https://eg.jumia.is/1.jpg\" src=\"data:image/png;base64,x\"></div>\n<div class=\"info\"><h3 class=\"name\">Philips Air Fryer 4.1L</h3><div class=\"prc\">EGP 2,999</div>\n<div class=\"s-prc-w\"><div class=\"old\">EGP 4,999</div><div class=\"bdg _dsct _sm\">40%</div></div>\n<div class=\"rev\"><div class=\"stars _s\">4.6 out of 5</div>(213)</div></div></a></article>\n<article class=\"prd\"><a class=\"core\" href=\"/no-sale-555555.html\" data-gtm-id=\"NOSALE\"><h3 class=\"name\">No sale</h3><div class=\"prc\">EGP 500</div></a></article></div>";
const AMAZON = "<div data-asin=\"B0CQMYP3LF\" data-component-type=\"s-search-result\">\n<a class=\"a-link-normal\" href=\"/Air-Fryer/dp/B0CQMYP3LF/ref=x\"><img class=\"s-image\" src=\"https://m.media-amazon.com/images/I/1.jpg\"></a>\n<h2 aria-label=\"Tefal Easy Fry Air Fryer 4.2L\"><span>Tefal Easy Fry Air Fryer 4.2L</span></h2>\n<span class=\"a-icon-alt\">4.5 out of 5 stars</span><span class=\"s-underline-text\">1,234</span>\n<span class=\"a-price\"><span class=\"a-offscreen\">EGP 3,199.00</span></span>\n<span class=\"a-price a-text-price\" data-a-strike=\"true\"><span class=\"a-offscreen\">EGP 4,599.00</span></span></div>\n<div data-asin=\"B0NOSTRIKE\" data-component-type=\"s-search-result\"><h2><span>Plain</span></h2>\n<span class=\"a-price\"><span class=\"a-offscreen\">EGP 100.00</span></span></div>";
const NOON = "<div class=\"grid\"><div class=\"ProductBox_box__1\"><a href=\"/egypt-ar/tefal-air-fryer/N70012345V/p/?o=abc\">\n<img src=\"https://f.nooncdn.com/p/1.jpg\" alt=\"Tefal Air Fryer 4.2L Black\">\n<div data-qa=\"product-name\" class=\"ProductDetails_name__x\">Tefal Air Fryer 4.2L Black</div>\n<div class=\"Price_wrapper\"><span class=\"Price_currency\">EGP</span><strong class=\"Price_amount__a\">2,499</strong>\n<span class=\"Price_oldPrice__z\">3,999</span></div></a></div>\n<div><a href=\"/egypt-ar/no/N7009/p/\"><img src=\"https://f.nooncdn.com/p/2.jpg\" alt=\"x\"><div data-qa=\"product-name\">Plain product here</div>\n<strong class=\"Price_amount__a\">100</strong></a></div></div>";
Deno.test("price", () => { assertEquals(price("EGP 1,299.00"), 1299); assertEquals(price("١٬٢٥٠٫٥ جنيه"), 1250.5); assertEquals(price("—"), null); });
Deno.test("jumia", () => {
  const [items, cards] = parseJumia(JUMIA);
  assertEquals([cards, items.length], [2, 1]);
  const i = items[0];
  assertEquals([i.id, i.url, i.image_url, i.current_price, i.original_price, i.rating, i.rating_count],
    ["jumia:PH123AA4XYZNAFAMZ", "https://www.jumia.com.eg/philips-air-fryer-123456789.html", "https://eg.jumia.is/1.jpg", 2999, 4999, 4.6, 213]);
});
Deno.test("amazon", () => {
  const [items, cards] = parseAmazon(AMAZON);
  assertEquals([cards, items.length], [2, 1]);
  const i = items[0];
  assertEquals([i.id, i.url, i.title, i.current_price, i.original_price, i.rating, i.rating_count],
    ["amazon:B0CQMYP3LF", "https://www.amazon.eg/dp/B0CQMYP3LF", "Tefal Easy Fry Air Fryer 4.2L", 3199, 4599, 4.5, 1234]);
});
Deno.test("amazon captcha", () => { assertThrows(() => parseAmazon('<form action="/errors/validateCaptcha">x</form>'), Blocked); });
Deno.test("noon generic", () => {
  const [items] = parseNoon(NOON);
  assertEquals(items.length, 1);
  const i = items[0];
  assertEquals([i.id.startsWith("noon:N70012345V-"), i.current_price, i.original_price, i.title, i.url],
    [true, 2499, 3999, "Tefal Air Fryer 4.2L Black", "https://www.noon.com/egypt-ar/tefal-air-fryer/N70012345V/p/?o=abc"]);
});
Deno.test("noon json", () => {
  const html = '<script>self.__next_f.push([1,"{\\"sku\\":\\"N70099887V\\",\\"name\\":\\"Blender Pro 600W\\",\\"url\\":\\"blender-pro\\",\\"price\\":2000,\\"sale_price\\":1500,\\"image_key\\":\\"v1/abc\\"}"])</script>';
  const [items] = parseNoon(html);
  assertEquals([items.length, items[0]?.id, items[0]?.current_price, items[0]?.original_price], [1, "noon:N70099887V", 1500, 2000]);
});
