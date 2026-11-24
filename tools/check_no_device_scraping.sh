#!/usr/bin/env bash
# Guard: the Android app must stay a pure client. Fails the build if scraping/browser-automation code or store
# fetch URLs appear in the app, or if background-worker permissions that a scraper would need come back.
set -u
# Exception approved by the owner (2026-11-23): assist/PhoneAssist.kt lets a phone read ONE server-queued page per
# minute (Wi-Fi, app open) for stores that block cloud servers. It contains no store URLs, no browser automation.
# Second approval (2026-11-25, owner: "any solution, even the user's phone, for Jumia only"): assist/JumiaWebView.kt
# opens one server-queued Jumia page in an invisible WebView to read its product grid. It is excluded below, Jumia only.
# Third approval (2026-11-25, owner: SHEIN section browsed "from the web, from the phone"): ui/shein/SheinStoreScreen.kt
# shows SHEIN Saudi in a visible in-app browser the user navigates; it reads only the open product's price/size/colour.
cd "$(dirname "$0")/../android/app/src/main"
patterns=(
  'org\.jsoup' 'Jsoup' 'android\.webkit\.WebView' 'evaluateJavascript' 'PageFetcher' 'WebFetcher' 'SheinPricer'
  'LiveScanService' 'FOREGROUND_SERVICE' 'amazon\.eg/s\?' 'jumia\.com\.eg/catalog' 'jumia\.com\.eg/flash'
  'noon\.com/egypt-ar/search' 'shein\.com/product-p' 'parse_amazon|parseAmazon|parse_jumia|parseJumia'
)
fail=0
for p in "${patterns[@]}"; do
  if hits=$(grep -rnE "$p" . 2>/dev/null | grep -vE '^\./java/com/dealsmasr/app/(assist/JumiaWebView|ui/shein/SheinStoreScreen)\.kt:'); then
    echo "::error::Device-side scraping is not allowed (pattern '$p'):"; echo "$hits"; fail=1
  fi
done
[ $fail -eq 0 ] && echo "✓ Android app contains no scraping code — it only reads from the server."
exit $fail
