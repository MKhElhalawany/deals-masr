package com.dealsmasr.app.scrape

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HTML fixtures mirror the store card markup (trimmed). Test-only data. */
class ScrapersTest {
    @Test fun prices() {
        assertEquals(1299.0, PriceText.parse("EGP 1,299.00")!!, 0.0)
        assertEquals(1250.5, PriceText.parse("١٬٢٥٠٫٥ جنيه")!!, 0.0)
        assertEquals(1000.0, PriceText.parse("EGP 1,000 - EGP 1,500")!!, 0.0)
        assertNull(PriceText.parse("—"))
    }

    @Test fun jumiaCards() {
        val html = """
        <div class="-paxs row _no-g _4cl-3cm-shs">
          <article class="prd _fb col c-prd">
            <a class="core" href="/philips-air-fryer-123456789.html" data-gtm-id="PH123AA4XYZNAFAMZ" data-gtm-name="Philips Air Fryer">
              <div class="img-c"><img class="img" data-src="https://eg.jumia.is/unsafe/fit-in/300x300/1.jpg" src="data:image/png;base64,x"></div>
              <div class="info"><h3 class="name">Philips Air Fryer 4.1L</h3>
                <div class="prc">EGP 2,999</div>
                <div class="s-prc-w"><div class="old">EGP 4,999</div><div class="bdg _dsct _sm">40%</div></div>
                <div class="rev"><div class="stars _s">4.6 out of 5</div>(213)</div>
              </div></a>
          </article>
          <article class="prd _fb col c-prd">
            <a class="core" href="/no-sale-555555.html" data-gtm-id="NOSALE"><div class="info"><h3 class="name">No sale item</h3>
              <div class="prc">EGP 500</div></div></a>
          </article>
        </div>"""
        val r = JumiaScraper.parse(Jsoup.parse(html, "https://www.jumia.com.eg/catalog/?q=air+fryer")) as ScrapeResult.Ok
        assertEquals(2, r.cardsSeen)
        assertEquals(1, r.items.size) // item without strike price is not a verifiable deal
        val i = r.items[0]
        assertEquals("jumia:PH123AA4XYZNAFAMZ", i.id)
        assertEquals("https://www.jumia.com.eg/philips-air-fryer-123456789.html", i.url)
        assertEquals("https://eg.jumia.is/unsafe/fit-in/300x300/1.jpg", i.imageUrl)
        assertEquals(2999.0, i.currentPrice, 0.0); assertEquals(4999.0, i.originalPrice, 0.0)
        assertEquals(40.0, i.discountPct!!, 0.0); assertEquals(4.6, i.rating!!, 0.0); assertEquals(213, i.ratingCount)
    }

    @Test fun amazonCards() {
        val html = """
        <div data-asin="B0CQMYP3LF" data-component-type="s-search-result" class="s-result-item s-asin">
          <a class="a-link-normal s-faceout-link" href="/Air-Fryer/dp/B0CQMYP3LF/ref=mp_s_a_1_1"><img class="s-image" src="https://m.media-amazon.com/images/I/1.jpg"></a>
          <h2 aria-label="Tefal Easy Fry Air Fryer 4.2L" class="a-size-base"><span>Tefal Easy Fry Air Fryer 4.2L</span></h2>
          <span class="a-icon-alt">4.5 out of 5 stars</span><span class="s-underline-text">1,234</span>
          <span class="a-price" data-a-size="xl"><span class="a-offscreen">EGP 3,199.00</span><span aria-hidden="true">3,199</span></span>
          <span class="a-price a-text-price" data-a-strike="true"><span class="a-offscreen">EGP 4,599.00</span></span>
        </div>
        <div data-asin="B0NOSTRIKE" data-component-type="s-search-result">
          <h2><span>Plain</span></h2><span class="a-price"><span class="a-offscreen">EGP 100.00</span></span>
        </div>"""
        val r = AmazonScraper.parse(Jsoup.parse(html, "https://www.amazon.eg/s?k=air+fryer")) as ScrapeResult.Ok
        assertEquals(2, r.cardsSeen)
        assertEquals(1, r.items.size)
        val i = r.items[0]
        assertEquals("amazon:B0CQMYP3LF", i.id)
        assertEquals("https://www.amazon.eg/dp/B0CQMYP3LF", i.url)
        assertEquals("Tefal Easy Fry Air Fryer 4.2L", i.title)
        assertEquals(3199.0, i.currentPrice, 0.0); assertEquals(4599.0, i.originalPrice, 0.0)
        assertEquals(30.4, i.discountPct!!, 0.0); assertEquals(4.5, i.rating!!, 0.0); assertEquals(1234, i.ratingCount)
    }

    @Test fun amazonCaptcha() {
        val html = """<html><body><form action="/errors/validateCaptcha"><p>Type the characters you see in this image:</p></form></body></html>"""
        assertTrue(AmazonScraper.parse(Jsoup.parse(html, "https://www.amazon.eg/s")) is ScrapeResult.Blocked)
    }
}
