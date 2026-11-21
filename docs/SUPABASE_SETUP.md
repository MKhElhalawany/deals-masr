# Setup: 2 steps, then everything is automatic

## You do (once)
1. **Sign in to Supabase**: https://supabase.com (with GitHub or Google).
2. **Create an access token and add it to GitHub:**
   - https://supabase.com/dashboard/account/tokens → *Generate new token* → copy it.
   - GitHub repo → Settings → Secrets and variables → Actions → *New repository secret*
     → Name `SUPABASE_ACCESS_TOKEN`, Secret = the token → *Add secret*.

That's it. Within about 15–30 minutes you'll get a GitHub e-mail **"📱 آخر نسخة من التطبيق"** with the APK link.

## Becoming admin (inside the app, no SQL)
Open the app → **«صاحب المتجر؟ ادخل بكود على الإيميل»** → type the e-mail of your Supabase account → enter the
6-digit code you receive → you're admin (Settings → إدارة الطلبات).
Only that e-mail, and only with the code from its inbox, can become admin.
(To use a different e-mail, add a secret `ADMIN_EMAIL`.)

## What runs automatically (`.github/workflows/build.yml`)
| When | What |
|---|---|
| every push, or every 15 min until the newest commit has a connected APK | tests → Supabase setup → APK → release |
| Supabase setup (`tools/supabase_setup.py`) | create/reuse project (Frankfurt) · auth config · migrations (once each) · owner e-mail · security gates |
| security gates (build stops on failure) | RLS on every table · anon blocked · role locked · full RLS suite on the live DB (rolled back) · real sign-up/order test through the public API |
| result (`tools/pipeline_report.py`) | success → comment with the APK link on "📱 آخر نسخة من التطبيق"; failure → "⚠️ التحديث التلقائي واقف" with the reason, retried hourly |

Secrets: the access token stays in GitHub Secrets (masked in logs); the DB password is random and never stored;
only the public URL and publishable key go into the APK.

## Before a public launch
Turn on e-mail confirmation and add an SMTP provider (Supabase → Authentication → Emails).
