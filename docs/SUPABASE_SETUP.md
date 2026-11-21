# Supabase setup: automated

Everything (project, database tables, triggers, RLS, auth settings, keys, APK build) is done by the
`supabase` job in `.github/workflows/build.yml` → `tools/supabase_setup.py`. It's safe to re-run: it creates
nothing twice and applies only new migrations.

## The only manual steps (they need your identity)
1. **Create a Supabase account**: https://supabase.com → Sign in with GitHub or Google.
2. **Create an access token**: https://supabase.com/dashboard/account/tokens → *Generate new token* → name `github-actions` → copy it (starts with `sbp_`).
3. **Give the token to GitHub as a secret**: repo → Settings → Secrets and variables → Actions → **Secrets** tab →
   *New repository secret*:
   - `SUPABASE_ACCESS_TOKEN` = the token
   - (optional) `ADMIN_EMAIL` = the email you'll sign up with in the app → you become admin automatically

Then the next build does the rest.

## What the automated job does
1. Finds or creates your organization and the project `deals-masr` (Frankfurt). The DB password is random, masked in logs, never stored.
2. Waits until the database, Auth and REST API are healthy.
3. Auth: email+password on, auto-confirm on (testing), min password length 6.
4. Applies `supabase/migrations/*.sql` once each (tracked in the private schema `app_private.migrations`).
5. Security gates (the build stops if any fails):
   - every public table has RLS; anonymous can't read orders; users can't change their role; sign-up trigger exists
   - the full RLS test suite `supabase/tests/rls_test.sql` runs on the live DB inside a rolled-back transaction
   - end-to-end test through the public API: sign up → profile → order → customer can't confirm → anonymous blocked → test user deleted
6. Reads the **publishable** key (refuses secret/service_role keys) and passes URL + key straight to the APK build.
7. Builds the APK and publishes it as the latest GitHub release.

## Before a public launch
- Turn auto-confirm off: Authentication → Email → Confirm email, and add an SMTP provider.
- Rotate or delete the access token if it's ever exposed: Account → Access Tokens.

## Changing settings later
Supabase → Table Editor → `app_config` (`shein_pricing`, `whatsapp_number`, `order_days`). Apps pick it up on next launch.
