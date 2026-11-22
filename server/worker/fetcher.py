"""Page fetching from the SERVER's own connection: plain HTTP first, real headless Chromium when a page needs
JavaScript or rejects plain clients. Optional PROXY_URL (e.g. a residential proxy) if a store blocks the server IP."""
from __future__ import annotations

import os
import time

import httpx

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/130.0.0.0 Safari/537.36")
HEADERS = {"User-Agent": UA, "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
           "Accept-Language": "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"}
PROXY = os.environ.get("PROXY_URL") or None


class Fetcher:
    def __init__(self):
        self.http = httpx.Client(timeout=30, follow_redirects=True, headers=HEADERS, proxy=PROXY, http2=False)
        self._pw = None
        self._browser = None

    def plain(self, url: str) -> tuple[int, str]:
        r = self.http.get(url)
        return r.status_code, r.text

    def _ensure_browser(self):
        if self._browser is None:
            from playwright.sync_api import sync_playwright
            self._pw = sync_playwright().start()
            launch = {"headless": True, "args": ["--disable-dev-shm-usage", "--no-sandbox"]}
            if PROXY:
                launch["proxy"] = {"server": PROXY}
            self._browser = self._pw.chromium.launch(**launch)
        return self._browser

    def rendered(self, url: str, settle_ms: int = 3500) -> tuple[int, str]:
        ctx = self._ensure_browser().new_context(locale="ar-EG", timezone_id="Africa/Cairo", user_agent=UA,
                                                 viewport={"width": 1366, "height": 900})
        # save bandwidth/CPU: no images, fonts or media
        ctx.route("**/*", lambda route: route.abort() if route.request.resource_type in ("image", "media", "font")
                  else route.continue_())
        page = ctx.new_page()
        try:
            resp = page.goto(url, wait_until="domcontentloaded", timeout=45_000)
            page.wait_for_timeout(settle_ms // 2)
            page.mouse.wheel(0, 4000)
            page.wait_for_timeout(settle_ms // 2)
            return (resp.status if resp else 0), page.content()
        finally:
            ctx.close()

    def close(self):
        try:
            if self._browser:
                self._browser.close()
            if self._pw:
                self._pw.stop()
        except Exception:
            pass
