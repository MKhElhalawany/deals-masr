function assertEquals(a: unknown, b: unknown) { const x = JSON.stringify(a), y = JSON.stringify(b); if (x !== y) throw new Error(`${x} !== ${y}`); }
import { parseTelegram, prices } from "./telegram.ts";

const page = `<div class="tgme_widget_message_wrap js-widget_message_wrap"><div class="tgme_widget_message js-widget_message" data-post="deals_me/120" data-view="x">
<a class="tgme_widget_message_photo_wrap 1 js-message_photo" style="width:800px;background-image:url('https://cdn4.telesco.pe/file/abc.jpg')" href="https://t.me/deals_me/120"></a>
<div class="tgme_widget_message_text js-message_text" dir="auto">🔥 قلاية هوائية فيليبس<br/>السعر ١٬٢٩٩ جنيه بدل ٢٬٤٠٠ جنيه<br/><a href="https://amzn.to/xyz" target="_blank">https://amzn.to/xyz</a></div>
<a class="tgme_widget_message_date" href="https://t.me/deals_me/120"><time datetime="2026-11-24T20:11:03+00:00" class="time">20:11</time></a></div></div>
<div class="tgme_widget_message_wrap"><div class="tgme_widget_message" data-post="deals_me/121">
<div class="tgme_widget_message_text js-message_text">كوبون خصم 20% &amp; شحن مجاني <a href="https://t.me/deals_me">@deals_me</a></div>
<time datetime="2026-11-24T21:00:00+00:00"></time></div></div>`;

Deno.test("telegram preview page → posts", () => {
  const p = parseTelegram(page, "deals_me");
  assertEquals(p.length, 2);
  assertEquals(p[0].post_id, 120);
  assertEquals(p[0].url, "https://amzn.to/xyz");
  assertEquals(p[0].image_url, "https://cdn4.telesco.pe/file/abc.jpg");
  assertEquals([p[0].price, p[0].old_price], [1299, 2400]);
  assertEquals(p[0].posted_at, "2026-11-24T20:11:03+00:00");
  assertEquals(p[1].url, null);
  assertEquals(p[1].text, "كوبون خصم 20% & شحن مجاني @deals_me");
  assertEquals([p[1].price, p[1].old_price], [null, null]);
});

Deno.test("prices from free text", () => {
  assertEquals(prices("بسعر 450 بدلا من 900"), { price: 450, old_price: 900 });
  assertEquals(prices("now 99 EGP was 199 EGP"), { price: 99, old_price: 199 });
  assertEquals(prices("خصم 30%"), { price: null, old_price: null });
});
