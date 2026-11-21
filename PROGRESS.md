# Progress

## Verified in this environment
- [x] Backend: 29 pytest tests pass (pricing spec example 100 SAR → 1,750 EGP, delivery fee once per order,
      tier boundaries, feed parsing CSV/JSON/XML, Amazon response parsing, SHEIN goods-id extraction,
      collector validation/dedupe/price-history/expiry, deals/search/filters/sort API, orders + idempotency,
      quote flow incl. server-side price override, admin auth/settings validation)
- [x] Backend boots with uvicorn; `/api/health`, `/api/marketplaces`, `/api/deals`, `/admin` respond
- [x] Android core logic (pure Kotlin) compiles; 9 JUnit tests pass (`android/tools/test-core.sh`):
      discount math, tiers, SHEIN pricing, countdown across midnight/clock-skew, milestones, link parsing,
      WhatsApp summary, sorting
- [x] Android sources parse with no syntax errors

## Not verified here (needs a normal network)
- [ ] Full Android Gradle build / APK: Maven Central and Google Maven were blocked in the build sandbox.
      CI workflow `.github/workflows/build.yml` builds the debug APK; first CI run may surface compile fixes.
- [ ] Live marketplace calls (need credentials, see docs/INTEGRATIONS.md)

## Phases
1. Foundation (RTL, theme, 5-tab nav, screens): done
2. Deals engine (providers, validation, tiers, cards, sort/filter): done
3. Smart search (multi-store, discount/price/category filters, pagination, alerts): done
4. SHEIN (link/image/name input, goods-id → SA link, admin quote, calculator, cart, WhatsApp): done
5. Orders (persistence, server sync, 35-day countdown, milestones): done
6. Testing: backend + core done; on-device testing pending APK build

## v1.1: serverless scraping mode
- [x] With no server configured, the app reads Amazon.eg search (pct-off filter) and Jumia catalogue pages
      directly from the phone (OkHttp + Jsoup), rotating 3 keywords per run, 1.5 s between requests
- [x] Every 10 min while open (foreground loop), every 15 min in background (WorkManager minimum)
- [x] Local price history (Room v2 `price_points`, migration 1→2), dedupe by store id, 48 h expiry
- [x] Per-store status in Settings (ok / blocked / 0 products) for diagnosing layout changes
- [ ] Selectors verified only against fixtures + Amazon live sample; GitHub runners are blocked by all 3 sites,
      so first live check happens on a phone. Noon not supported (client-rendered).

## v2.0: Supabase backend
- [x] Email/password Login, Sign-up, Reset via Supabase Auth (REST, no SDK); tokens in Keystore-encrypted prefs, auto-refresh
- [x] Schema + RLS migration `supabase/migrations/20261121000000_init.sql`: profiles (role), user_settings, stores,
      deals, price_history, favorites, alerts, orders, app_config; auto profile on sign-up; server-stamped confirmed_at
- [x] RLS tests `supabase/tests/rls_test.sql` (verified locally on Postgres 16 + run in CI; mutation-checked)
- [x] Orders saved to Supabase; admin "إدارة الطلبات" screen confirms/advances orders; customer gets
      "تم تأكيد طلبك" notification + celebration (polling: 2 min foreground / 15 min background)
- [x] Admin role from DB replaces the old PIN
- [ ] Favorites/alerts/settings cloud sync, server-side scraping into `deals` (tables + RLS ready)
- [ ] Google / phone sign-in (needs Google Cloud OAuth client / SMS provider in Supabase)

## Next
- Run CI, fix any compile errors, install APK, smoke-test on a device
- Obtain Amazon Associates EG / Jumia KOL / noon network feed access and set env vars
- Optional: FCM push, customer auth, Room migrations once schema changes
