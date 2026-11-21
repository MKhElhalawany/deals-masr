# Supabase setup (one time, about 10 minutes, free)

Supabase hosts the login system and the database. There's no server for you to run.

## 1. Create the project
1. Go to **https://supabase.com** → **Start your project** → sign in with GitHub or Google.
2. **New project**
   - Name: `deals-masr`
   - Database password: tap **Generate**, then save it somewhere safe (you won't need it in the app).
   - Region: **Central EU (Frankfurt)** (closest to Egypt).
3. Wait about 2 minutes until the project is ready.

## 2. Create the database
1. Left menu → **SQL Editor** → **New query**.
2. Open `supabase/migrations/20261121000000_init.sql` from the GitHub repo, copy **all** of it, paste it, and press **Run**.
   You should see "Success. No rows returned".

## 3. Login settings
Left menu → **Authentication** → **Sign In / Providers** → **Email**: make sure it's enabled.
- Leave **Confirm email** ON for real users. Each new user then gets a confirmation email before the first login.
  Supabase's built-in email sender only sends a few emails per hour, so before launch add your own email service under Authentication → **Emails** → SMTP settings.
- Or turn it OFF while testing, so sign-up logs in immediately.

## 4. Give the app the project address
Left menu → **Project Settings** → **API Keys** (and **Data API** for the URL). Copy:
- **Project URL**, for example `https://abcdxyz.supabase.co`
- **anon / publishable key**: the *public* key, safe to put in an app.

⚠️ Never share the **service_role / secret** key. The app doesn't need it.

Then either send those two values to Claude, or add them yourself on GitHub:
repo → **Settings** → **Secrets and variables** → **Actions** → **Variables** tab → **New repository variable**
- `SUPABASE_URL` = Project URL
- `SUPABASE_ANON_KEY` = anon/publishable key

Every push to `main` then builds an APK connected to your project.

## 5. Make yourself admin
1. Install the new APK and **create your account** in the app.
2. Supabase → **SQL Editor** → run (with your email):
   ```sql
   update public.profiles set role = 'admin'
   where id = (select id from auth.users where email = 'YOUR_EMAIL@gmail.com');
   ```
3. Log out and back in. You'll see **إدارة الطلبات** in Settings, plus the admin tools.

## What's protected (Row Level Security)
| Table | Customers | Admin |
|---|---|---|
| profiles | read and edit own name/phone (not role) | read all |
| orders | create (only as "submitted") and read own | read all, change status |
| deals, stores, price_history, app_config | read | write |
| favorites, alerts, user_settings | own rows only | own rows only |
| anyone not signed in | nothing | |

The confirmation time (start of the 25–35 day countdown) is set by the database, not by the phone.
Tested in CI: `supabase/tests/rls_test.sql` runs on every push.

## Changing settings later
Supabase → **Table Editor** → `app_config`:
- `shein_pricing` holds the conversion rate, multiplier and delivery fee.
- `whatsapp_number` is the WhatsApp number orders are sent to.
- `order_days` is the delivery window, `{"min":25,"max":35}`.

Apps pick up changes the next time they open.
