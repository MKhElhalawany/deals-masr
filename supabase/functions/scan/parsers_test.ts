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
Deno.test("amazon deal candidate + product page", async () => {
  const { parseAmazon, parseAmazonProduct, amazonCandidates } = await import("./parsers.ts");
  parseAmazon('<div data-asin="B0X1" data-component-type="s-search-result"><img class="s-image" src="https://m/1.jpg"><h2 aria-label="Pajama Set"></h2><span class="a-badge" data-a-badge-type="deal">Deal</span><span class="a-price"><span class="a-offscreen">EGP 745.00</span></span></div>');
  const m = await import("./parsers.ts");
  assertEquals(m.amazonCandidates.map((c) => [c.asin, c.current]), [["B0X1", 745]]);
  assertEquals(parseAmazonProduct('<div id="corePriceDisplay_desktop_feature_div"><span class="priceToPay"><span class="a-offscreen">EGP 745.00</span></span><span class="basisPrice"><span class="a-price a-text-price"><span class="a-offscreen">EGP 1,199.00</span></span></span></div>'), { current: 745, list: 1199 });
});
Deno.test("noon: no search pages (robots.txt), category pages only", async () => {
  const { STORES } = await import("./parsers.ts");
  assertEquals(STORES.noon.search, null);
  assertEquals(STORES.noon.deals.length > 0 && STORES.noon.deals.every((u) => u.startsWith("https://www.noon.com/egypt-en/") && !u.includes("/search")), true);
});
Deno.test("discovery: robots, links, pages, fingerprint", async () => {
  const { Robots, categoryLinks, nextPage, fingerprint } = await import("./discover.ts");
  const rb = new Robots("User-agent: *\nDisallow: /*/search/\nDisallow: /collections/*sort_by*\n");
  assertEquals(rb.ok("https://www.noon.com/egypt-ar/search/?q=x"), false);
  const noon = categoryLinks("noon", '<a href="/egypt-en/mobiles/">m</a><a href="/egypt-en/x/p/">p</a><a href="/egypt-en/search/">s</a><a href="/egypt-en/fashion/men-31225/">f</a>', 0, rb);
  assertEquals(noon.map((t) => t.url), ["https://www.noon.com/egypt-en/mobiles/?limit=50", "https://www.noon.com/egypt-en/fashion/men-31225/?limit=50"]);
  const amz = categoryLinks("amazon", '<a href="/s?i=electronics&amp;rh=n%3A21832883031%2Cp_n_deal_type%3A1&amp;dc&amp;qid=1&amp;ref=sr_nr">E</a><a href="/dp/B01">x</a>', 0, rb);
  assertEquals(amz.length, 1);
  assertEquals(amz[0].url.includes("pct-off=10-") && amz[0].url.includes("rh=n%3A21832883031"), true);
  const tt = categoryLinks("townteam", '<a href="/collections/spring-offers">s</a><a href="/collections/all?sort_by=price">x</a><a href="/products/a">p</a>', 0, rb);
  assertEquals(tt.map((t) => t.url), ["https://townteam.com/collections/spring-offers", "https://townteam.com/collections/all"]);  // sort params are dropped
  assertEquals(categoryLinks("noon", '<a href="/egypt-en/mobiles/">m</a>', 3, rb).length, 0);
  assertEquals(nextPage("noon", "https://www.noon.com/egypt-en/mobiles/?limit=50", 50, 0, 1)?.url, "https://www.noon.com/egypt-en/mobiles/?limit=50&page=2");
  assertEquals(nextPage("noon", "https://www.noon.com/egypt-en/mobiles/?limit=50&page=3", 50, 0, 1), null);
  assertEquals(nextPage("noon", "https://www.noon.com/egypt-en/mobiles/?limit=50", 0, 0, 1), null);
  assertEquals(fingerprint([{ id: "a", current_price: 1 }, { id: "b", current_price: 2 }]), fingerprint([{ id: "b", current_price: 2 }, { id: "a", current_price: 1 }]));
});
Deno.test("town team product JSON", async () => {
  const { parseTownTeamJson, townTeamJsonUrl } = await import("./parsers.ts");
  assertEquals(townTeamJsonUrl("https://townteam.com/collections/spring-offers?page=2"), "https://townteam.com/collections/spring-offers/products.json?limit=250&page=2");
  assertEquals(townTeamJsonUrl("https://townteam.com/"), null);
  const [items, n] = parseTownTeamJson(JSON.stringify({ products: [
    { handle: "jeans-skinny", title: "Jeans Skinny fit basic - D-BLUE", images: [{ src: "//cdn.shopify.com/j.jpg" }],
      variants: [{ price: "349.00", compare_at_price: "499.00", available: true }, { price: "299.00", compare_at_price: "499.00", available: false }] },
    { handle: "no-sale", title: "Tee", images: [], variants: [{ price: "199.00", compare_at_price: null, available: true }] }] }));
  assertEquals([n, items.length, items[0].id, items[0].current_price, items[0].original_price, items[0].image_url],
    [2, 1, "townteam:jeans-skinny", 349, 499, "https://cdn.shopify.com/j.jpg"]);
});
