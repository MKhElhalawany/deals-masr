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

## v3.1 — Realtime + push (fastest delivery)
- Server: one parallel scan loop per store (STORE_INTERVAL_SEC=8 between pages of the same store; the deal page is visited every other step), plus a queue loop every 2 s for SHEIN prices, searches and orders. Writes go straight to Supabase.
- Supabase Realtime publication covers deals, user_notifications, orders and shein_quotes, with RLS applied per user. New `device_tokens` table (own rows only).
- Android: `RealtimeClient` (one WebSocket while the app is visible) writes changes into Room immediately. Polling now only runs while the socket is down, plus a catch-up after each reconnect.
- FCM: data-only, high-priority pushes from the worker.
  - Deals go to topics `deals_min_{10,30,50,70}` through topic conditions.
  - Deals of 10–29% are batched into a summary every 5 minutes.
  - Alerts, favourites and orders are sent to each user's own device tokens.
  - Duplicates are prevented between Realtime and push.
- CI: a `firebase` job (registers the app, outputs public ids) and a `latency` job (measured realtime delivery, FCM accept time and worker status), whose results go into the release issue.

## v3.2 — free 24/7 scanner on Supabase (no VM, no card)
- `supabase/functions/scan` (Deno): Amazon, Jumia and Noon parsers (no browser), FCM sender, best-effort SHEIN pricing. Called only by the database with a secret header.
- pg_cron runs every 30 s per store. Triggers call the function instantly for new search requests, SHEIN quotes and order status changes (pushes).
- `public.ingest_deals` RPC, shared by the edge scanner and the VM worker:
  - only real changes are written, so Realtime and push stay quiet otherwise;
  - writes price history and alert/favourite notifications;
  - tested in `rls_test.sql`.
- The edge scanner steps aside automatically when the Oracle VM worker is alive.
