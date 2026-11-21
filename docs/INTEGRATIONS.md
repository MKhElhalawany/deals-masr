# Marketplace data sources: what's real and what needs access

The app **never fabricates prices, discounts, search results or availability**. Every deal shown comes from a
provider in `backend/app/providers/`, is checked by `collector.validate()` (EGP only, real original price,
current < original, discount ≥ admin threshold, ≤ 95 %), and is stamped with `last_checked`.
A provider without credentials reports `is_configured() == False` and is skipped. The app then shows an honest
"not connected" state instead of sample data.

Checked November 2026.

| Source | Official access path | Status in this repo | What you need |
|---|---|---|---|
| **Amazon Egypt** | **Amazon Creators API** (successor to PA-API 5, which Amazon retired in 2026). The EU region covers `amazon.eg`. | **Implemented**: `amazon_creators.py` (OAuth client-credentials via `api.amazon.co.uk/auth/o2/token`, `searchItems` with `minSavingPercent`, OffersV2 `savingBasis` used as the verified original price). Parser unit-tested against the documented response shape. **Not yet tested against the live API** (no credentials). | Approved **Amazon Associates Egypt** account (affiliate-program.amazon.eg; historically invite-only) and Creators API credentials. Amazon requires **10 qualifying sales in the past 30 days** before it grants catalogue API access. Set `AMAZON_CLIENT_ID`, `AMAZON_CLIENT_SECRET`, `AMAZON_PARTNER_TAG`. |
| **Jumia Egypt** | **Jumia KOL / affiliate programme**, which supplies deep links and **product feeds**. Jumia has no public product/price API. | **Implemented**: generic `FeedProvider` (CSV / JSON / Google-Shopping XML, Arabic digits handled). Feed snapshots are complete, so items that disappear from the feed or lose their discount are expired automatically. | Affiliate approval plus the feed URL issued to your account → `JUMIA_FEED_URL`. If your feed uses unusual column names, add them to `FIELD_ALIASES` in `feed.py`. |
| **Noon Egypt** | Noon's affiliate programme runs through affiliate networks (several network listings are closed or coupon-only). The **noon Partner API** covers sellers' own catalogue only, not marketplace-wide deals. | **Implemented**: the same `FeedProvider`, ready for a network product feed. | A network/partner that issues a noon Egypt **product feed** → `NOON_FEED_URL`. If you can only get coupon codes, noon deals can't be listed automatically. |
| **SHEIN (Saudi)** | The **SHEIN affiliate programme** gives links/codes only. **SHEIN Open API** is for sellers. There's no public product or price API. | **Implemented as admin-assisted quoting**: the app extracts the `goods_id` from the pasted link (or follows a share short-link) and builds the matching `sa.shein.com` URL. The request goes to the admin queue, where the admin confirms it's the same product and enters the original (pre-sale) SAR price, or sends candidate matches for the customer to choose from. Nothing is priced until a real price exists. | Nothing to start. To automate, plug a licensed data source into `SheinPriceSource.lookup()` in `shein.py`. |

## Why there's no scraping
Third-party scrapers exist for all four sites, but they break often, get blocked, and conflict with the sites'
terms. Per the brief, the architecture doesn't depend on them. If you obtain a *licensed* data provider,
add a new `Provider` subclass and register it in `providers/__init__.py`. Nothing else changes.

## Adding or replacing a marketplace
1. Create `backend/app/providers/<name>.py` with a `Provider` subclass (`is_configured`, `fetch_deals`, optional `live_search`).
2. Register it in `providers/__init__.py → registry()` and add a row to `DEFAULT_MARKETPLACES` in `db.py`.
3. Add the store chip in `android/.../ui/deals/DealsScreen.kt → STORES`.

## Push notifications (FCM), ready but not enabled
The backend writes "major deal" events to the `notifications` table (`GET /api/notifications`). The app polls
it in `DealRefreshWorker` every 6 h and shows local notifications. For real-time push, add Firebase
(`google-services.json`, `firebase-messaging`), send from the backend when a row is inserted, and forward
received messages to `Notifier.show()`. It isn't included because it needs your Firebase project credentials.
