# عروض مصر — Deals Masr

An Arabic-first (RTL) Android app for the Egyptian market:

* **Deals aggregator** for Amazon Egypt, Jumia Egypt and Noon Egypt, grouped by real discount tier
  (Deals 10–29 %, Hot 30–49 %, Super 50–69 %, Mega 70 %+; tiers are configurable).
* **Smart search** across selected stores with minimum-discount, price, category and sort filters, plus
  "notify me when X is ≥ N % off" alerts.
* **SHEIN ordering service**: add products by link, image or name; transparent pricing
  `(SAR × 13.5 × 1.20) + 130 EGP once per order`; cart; WhatsApp order summary; persistent 35-day countdown.

```
android/   Kotlin · Jetpack Compose · Material 3 · MVVM · Room · Retrofit · WorkManager · DataStore
backend/   Python · FastAPI · SQLite: deal collection, price history, SHEIN quotes, orders, admin panel
docs/      INTEGRATIONS.md: which data sources are live and what credentials they need
```

## 1. Backend

```bash
cd backend
pip install -r requirements.txt
cp .env.example .env        # set ADMIN_TOKEN and any marketplace credentials
set -a; . ./.env; set +a
uvicorn app.main:app --host 0.0.0.0 --port 8000
# tests
pip install pytest && python -m pytest -q
```

Docker: `docker build -t deals-api backend && docker run -p 8000:8000 --env-file backend/.env -v deals-data:/data deals-api`

* Admin panel: `http://<server>:8000/admin` (Arabic UI; paste `ADMIN_TOKEN`). From there you can:
  * edit the SHEIN formula (rate, multiplier, delivery fee), discount tiers, minimum discount, refresh interval,
    expiry window, countdown days and the WhatsApp order number
  * enable or disable marketplaces, run a refresh, purge expired deals
  * price SHEIN quote requests (or send candidate matches), and manage order status. Setting an order to
    **confirmed** starts the customer's 35-day countdown.
* The collector runs automatically every `refresh_minutes` (default 180). It dedupes products by store id and
  canonical URL, records price history on every change, detects new, changed and expired deals, and queues
  "major deal" notifications.

## 2. Android app

Requirements: JDK 17 and Android SDK 35 (Android Studio Ladybug or newer works).

```bash
cd android
./gradlew testDebugUnitTest                      # unit tests
./gradlew assembleDebug -PapiBaseUrl=https://your-server/
# APK → android/app/build/outputs/apk/debug/app-debug.apk
```

`apiBaseUrl` is optional. Users can set or change the server under **الإعدادات → خادم العروض**.
For the Android emulator talking to a local backend, use `http://10.0.2.2:8000/` (cleartext is allowed in
debug builds only).

Without a server, the deals tabs show a "not connected" state; the app never displays sample products.
The SHEIN section still works with the SAR price the customer enters, and orders are confirmed on send.

### Building the APK on GitHub
`.github/workflows/build.yml` runs the backend tests and the Android unit tests, then builds the debug APK and
uploads it as the `deals-masr-debug-apk` artifact. Optionally set the repository variable `API_BASE_URL`.

## 3. How key features work

| Feature | Where |
|---|---|
| Discount math `((orig − cur) / orig) × 100`, tiers, sorting | `android/.../core/DealMath.kt`, `backend/app/pricing.py` |
| SHEIN pricing, delivery fee once per order | `core/SheinPricing.kt`, `pricing.py` (server recomputes and trusts quoted prices over client input) |
| 35-day countdown from the stored confirmation timestamp (Africa/Cairo calendar days) | `core/OrderCountdown.kt`. The UI ticks every minute; `OrderMilestoneWorker` sends milestone notifications (30/21/14/7/3/1/0 days left) |
| WhatsApp summary | `core/OrderSummary.kt`, `ui/shein/WhatsApp.kt` |
| Offline cache, favourites, alerts, cart, orders | Room (`data/local/Database.kt`) |
| Periodic refresh (6 h), price-drop, alert and major-deal notifications | `work/Workers.kt` |
| Share a SHEIN link from any app → SHEIN cart | `MainActivity` `ACTION_SEND` handler |

## 4. Security notes
* No secrets in the app. Marketplace keys live only in backend env vars, and the admin API needs a bearer token
  (constant-time compare).
* Customers check order status with order id + phone number. Add real auth (e.g. Firebase phone auth) before
  scaling.
* Uploaded quote images are stored server-side (8 MB cap) and only served to the admin.
