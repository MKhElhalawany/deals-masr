#!/usr/bin/env bash
# Guard: the Android app must stay a pure client. Fails the build if scraping/browser-automation code or store
# fetch URLs appear in the app, or if background-worker permissions that a scraper would need come back.
set -u
cd "$(dirname "$0")/../android/app/src/main"
patterns=(
  'org\.jsoup' 'Jsoup' 'android\.webkit\.WebView' 'evaluateJavascript' 'PageFetcher' 'WebFetcher' 'SheinPricer'
  'LiveScanService' 'FOREGROUND_SERVICE' 'amazon\.eg/s\?' 'jumia\.com\.eg/catalog' 'jumia\.com\.eg/flash'
  'noon\.com/egypt-ar/search' 'shein\.com/product-p' 'parse_amazon|parseAmazon|parse_jumia|parseJumia'
)
fail=0
for p in "${patterns[@]}"; do
  if hits=$(grep -rnE "$p" . 2>/dev/null); then
    echo "::error::Device-side scraping is not allowed (pattern '$p'):"; echo "$hits"; fail=1
  fi
done
[ $fail -eq 0 ] && echo "✓ Android app contains no scraping code — it only reads from the server."
exit $fail
