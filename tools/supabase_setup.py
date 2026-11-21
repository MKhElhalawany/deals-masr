#!/usr/bin/env python3
"""
Fully automated, idempotent Supabase provisioning for Deals Masr (runs in GitHub Actions).

Needs ONE secret: SUPABASE_ACCESS_TOKEN (personal access token). Optional secret: ADMIN_EMAIL.
Steps: find/create org → find/create project "deals-masr" → wait healthy → auth config → apply new
migrations → live security checks (+ RLS test suite inside a rolled-back transaction) → end-to-end
smoke test through the public API → grant admin → emit SUPABASE_URL + publishable key for the APK build.

Security: never prints or outputs secret/service_role keys; DB password is random, masked, not stored
(migrations go through the Management API, which doesn't need it).
"""
import glob
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("SUPABASE_API_BASE", "https://api.supabase.com/v1")
TOKEN = os.environ.get("SUPABASE_ACCESS_TOKEN", "").strip()
ADMIN_EMAIL = os.environ.get("ADMIN_EMAIL", "").strip()
PROJECT_NAME = os.environ.get("SUPABASE_PROJECT_NAME", "deals-masr")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def log(msg):
    print(msg, flush=True)


def report(key, value):
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a") as f:
            f.write(f"{key}={value}\n")


def fail(msg):
    """Stop with a plain-language reason (shown in the run summary and the failure issue)."""
    print(f"::error::{msg}", flush=True)
    report("error", msg.replace("\n", " ")[:600])
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(f"### ❌ Supabase setup failed\n\n{msg}\n")
    sys.exit(1)


def http(method, url, body=None, headers=None, ok=(200, 201, 204), retries=5):
    data = json.dumps(body).encode() if body is not None else None
    for attempt in range(retries):
        req = urllib.request.Request(url, data=data, method=method, headers={
            "Content-Type": "application/json", "Accept": "application/json", **(headers or {})})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                raw = r.read().decode()
                return r.status, (json.loads(raw) if raw.strip() else None)
        except urllib.error.HTTPError as e:
            raw = e.read().decode(errors="replace")
            if e.code in (429, 500, 502, 503, 504) and attempt < retries - 1:
                time.sleep(5 * (attempt + 1)); continue
            if e.code in ok:
                return e.code, None
            return e.code, raw
        except urllib.error.URLError:
            if attempt < retries - 1:
                time.sleep(5 * (attempt + 1)); continue
            raise


def api(method, path, body=None):
    status, res = http(method, API + path, body, {"Authorization": f"Bearer {TOKEN}"})
    if status in (401, 403) and path in ("/organizations", "/projects"):
        fail("The SUPABASE_ACCESS_TOKEN secret is invalid or expired. Create a new token at "
             "https://supabase.com/dashboard/account/tokens and replace the GitHub secret.")
    if status >= 400:
        fail(f"Management API {method} {path} → {status}: {str(res)[:300]}")
    return res


def sql(ref, query):
    status, res = http("POST", f"{API}/projects/{ref}/database/query", {"query": query},
                       {"Authorization": f"Bearer {TOKEN}"})
    if status >= 400:
        raise RuntimeError(f"SQL failed ({status}): {str(res)[:500]}")
    return res


def guarded(fn, what):
    try:
        return fn()
    except RuntimeError as e:
        fail(f"{what}: {e}")


def org_slug():
    orgs = api("GET", "/organizations") or []
    if orgs:
        o = orgs[0]
        return o.get("slug") or o["id"]
    log("No organization yet → creating one")
    o = api("POST", "/organizations", {"name": "deals-masr"})
    return o.get("slug") or o["id"]


def project(slug):
    for p in api("GET", "/projects") or []:
        if p["name"] == PROJECT_NAME:
            log(f"Using existing project {p['ref']} ({p.get('region')})")
            return p["ref"]
    password = secrets.token_urlsafe(24)
    print(f"::add-mask::{password}", flush=True)
    log("Creating project (Europe region)…")
    body = {"name": PROJECT_NAME, "organization_slug": slug, "db_pass": password,
            "region_selection": {"type": "specific", "code": "eu-central-1"}}
    status, res = http("POST", API + "/projects", body, {"Authorization": f"Bearer {TOKEN}"})
    if status >= 400:  # older API shape
        body.pop("region_selection"); body["region"] = "eu-central-1"
        status, res = http("POST", API + "/projects", body, {"Authorization": f"Bearer {TOKEN}"})
    if status >= 400:
        fail(f"Could not create project ({status}): {str(res)[:300]}. "
             "Free plan allows 2 active projects — pause/delete one in the dashboard, or check the token permissions.")
    return res["ref"]


def wait_healthy(ref):
    log("Waiting for the project to be ready…")
    for _ in range(80):  # up to ~13 min
        p = api("GET", f"/projects/{ref}")
        if p.get("status") == "ACTIVE_HEALTHY":
            status, health = http("GET", f"{API}/projects/{ref}/health?services=auth&services=rest&services=db",
                                  headers={"Authorization": f"Bearer {TOKEN}"})
            if status < 400 and health and all(s.get("healthy") or s.get("status") == "ACTIVE_HEALTHY" for s in health):
                log("Project healthy ✓"); return
        time.sleep(10)
    fail("Project did not become healthy in time — rerun the workflow in a few minutes.")


OTP_EMAIL = """<div dir="rtl" style="font-family:Tahoma,Arial,sans-serif;font-size:16px">
<p>كود الدخول لتطبيق <b>عروض مصر</b>:</p>
<p style="font-size:32px;letter-spacing:6px"><b>{{ .Token }}</b></p>
<p>لو مش انت اللي طلبته تجاهل الرسالة.</p></div>"""


def configure_auth(ref):
    api("PATCH", f"/projects/{ref}/config/auth", {
        "external_email_enabled": True,
        "mailer_autoconfirm": True,          # testing: sign-up logs in immediately (turn off before launch)
        "password_min_length": 6,
        "disable_signup": False,
        "mailer_otp_length": 6,
        "mailer_otp_exp": 600,
        "mailer_subjects_magic_link": "كود الدخول — عروض مصر",
        "mailer_templates_magic_link_content": OTP_EMAIL,   # owner login code
    })
    log("Auth configured ✓ (email+password, auto-confirm for testing, 6-digit owner login code)")


def owner_email():
    """The Supabase account owner's e-mail (or ADMIN_EMAIL if set) becomes the app owner."""
    if ADMIN_EMAIL:
        return ADMIN_EMAIL
    status, prof = http("GET", f"{API}/profile", headers={"Authorization": f"Bearer {TOKEN}"})
    if status < 400 and isinstance(prof, dict):
        return (prof.get("primary_email") or prof.get("email") or "").strip()
    return ""


def masked(e):
    name, _, dom = e.partition("@")
    return (name[:2] + "***@" + dom) if dom else "***"


def set_owner(ref):
    email = owner_email()
    if not email or "@" not in email:
        log("::warning::Could not detect the owner e-mail. Add a repository secret ADMIN_EMAIL to enable the admin login.")
        report("owner", "")
        return
    safe = email.replace("'", "''")
    sql(ref, f"insert into app_private.settings(key, value) values ('owner_email', '{safe}') "
             f"on conflict (key) do update set value = excluded.value;")
    admins = sql(ref, "select count(*) as n from public.profiles where role = 'admin'")
    report("owner", masked(email))
    report("admin_ready", "yes" if admins and int(admins[0]["n"]) > 0 else "no")
    log(f"Owner e-mail set ✓ ({masked(email)}) — admin is granted when this e-mail logs in with its e-mail code")


def apply_migrations(ref):
    sql(ref, """
      create schema if not exists app_private;
      revoke all on schema app_private from public, anon, authenticated;
      create table if not exists app_private.migrations (name text primary key, applied_at timestamptz not null default now());
    """)
    done = {r["name"] for r in sql(ref, "select name from app_private.migrations") or []}
    files = sorted(glob.glob(os.path.join(ROOT, "supabase", "migrations", "*.sql")))
    for f in files:
        name = os.path.basename(f)
        if name in done:
            log(f"  = {name} (already applied)"); continue
        body = open(f, encoding="utf-8").read()
        sql(ref, f"begin;\n{body}\ninsert into app_private.migrations(name) values ('{name}');\ncommit;")
        log(f"  + {name} applied ✓")


def security_checks(ref):
    r = sql(ref, """
      select json_build_object(
        'no_rls', (select coalesce(json_agg(c.relname), '[]'::json) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = 'public' and c.relkind = 'r' and not c.relrowsecurity),
        'anon_can_read_orders', has_table_privilege('anon', 'public.orders', 'select'),
        'users_can_change_role', has_column_privilege('authenticated', 'public.profiles', 'role', 'update'),
        'signup_trigger', exists (select 1 from pg_trigger where tgname = 'on_auth_user_created')
      ) as r""")[0]["r"]
    if isinstance(r, str):
        r = json.loads(r)
    problems = []
    if r["no_rls"]: problems.append(f"tables without RLS: {r['no_rls']}")
    if r["anon_can_read_orders"]: problems.append("anon can read orders")
    if r["users_can_change_role"]: problems.append("users can change their role")
    if not r["signup_trigger"]: problems.append("sign-up trigger missing")
    if problems:
        fail("Security check failed: " + "; ".join(problems))
    log("Security checks ✓ (RLS on every table, anon blocked, role locked, trigger present)")

    # Full RLS regression suite against the real database, inside a transaction that is rolled back.
    tests = open(os.path.join(ROOT, "supabase", "tests", "rls_test.sql"), encoding="utf-8").read()
    tests = "\n".join(l for l in tests.splitlines() if not l.startswith("\\"))
    tests = tests.replace("'ALL RLS TESTS PASSED'", "'ALL RLS TESTS PASSED' as result")
    try:
        sql(ref, f"begin;\n{tests}\nrollback;")
    except RuntimeError as e:
        sql(ref, "rollback;") if "aborted" in str(e) else None
        fail(f"RLS test suite failed on the live database: {e}")
    log("RLS test suite on live database ✓ (rolled back, no data left)")


def publishable_key(ref):
    status, keys = http("GET", f"{API}/projects/{ref}/api-keys?reveal=false", headers={"Authorization": f"Bearer {TOKEN}"})
    keys = keys if status < 400 and isinstance(keys, list) else []
    pub = next((k for k in keys if k.get("type") == "publishable" and k.get("api_key")), None)
    if pub is None:
        status, created = http("POST", f"{API}/projects/{ref}/api-keys", {"type": "publishable", "name": "default"},
                               {"Authorization": f"Bearer {TOKEN}"})
        if status < 400 and isinstance(created, dict) and created.get("api_key"):
            pub = created
    if pub is None:  # legacy projects: the anon JWT is the public key
        pub = next((k for k in keys if k.get("name") == "anon" and k.get("api_key")), None)
    if pub is None:
        fail("Could not read the publishable key (token needs 'API keys: read').")
    key = pub["api_key"]
    if key.startswith("sb_secret_") or pub.get("name") == "service_role":
        fail("Refusing to use a secret key in the app.")
    return key


def smoke_test(url, key):
    """Real sign-up → profile → order insert → forbidden self-confirm → cleanup, through the public API."""
    email = f"ci-smoke-{int(time.time())}-{secrets.token_hex(3)}@example.com"
    pw = secrets.token_urlsafe(16)
    h = {"apikey": key}
    st, s = http("POST", f"{url}/auth/v1/signup", {"email": email, "password": pw, "data": {"full_name": "CI Smoke"}}, h)
    if st >= 400 or not isinstance(s, dict) or "access_token" not in s:
        log(f"::warning::Smoke test sign-up returned {st}: {str(s)[:200]}"); return None
    auth = {**h, "Authorization": f"Bearer {s['access_token']}"}
    st, prof = http("GET", f"{url}/rest/v1/profiles?select=role", headers=auth)
    assert st == 200 and prof and prof[0]["role"] == "user", f"profile: {st} {prof}"
    oid = "CI" + secrets.token_hex(4).upper()
    st, _ = http("POST", f"{url}/rest/v1/orders", {"id": oid, "customer_name": "CI Smoke", "customer_phone": "01000000000",
              "items": [{"title": "test"}], "subtotal_egp": 1, "delivery_fee_egp": 1, "total_egp": 2}, auth)
    assert st in (200, 201), f"order insert {st}"
    http("PATCH", f"{url}/rest/v1/orders?id=eq.{oid}", {"status": "confirmed"}, {**auth, "Prefer": "return=minimal"})
    st, rows = http("GET", f"{url}/rest/v1/orders?id=eq.{oid}&select=status", headers=auth)
    assert rows and rows[0]["status"] == "submitted", f"customer could change status: {rows}"
    st, anon_rows = http("GET", f"{url}/rest/v1/orders?select=id", headers=h)
    assert st >= 400 or anon_rows == [], f"anonymous read returned data: {st}"
    log("End-to-end smoke test ✓ (sign-up, profile, order, status locked, anonymous blocked)")
    return email


def main():
    if not TOKEN:
        log("::notice::SUPABASE_ACCESS_TOKEN secret not set — skipping Supabase setup (APK will show 'not connected').")
        return
    ref = project(org_slug())
    wait_healthy(ref)
    configure_auth(ref)
    guarded(lambda: apply_migrations(ref), "Applying database migrations failed")
    guarded(lambda: security_checks(ref), "Security checks could not run")
    url = f"https://{ref}.supabase.co"
    key = publishable_key(ref)
    smoke_email = None
    sql(ref, "delete from auth.users where email like 'ci-smoke-%@example.com';")  # leftovers of interrupted runs
    if os.environ.get("SKIP_SMOKE") != "1":
        try:
            smoke_email = smoke_test(url, key)
        except AssertionError as e:
            fail(f"End-to-end security smoke test failed: {e}")
        except Exception as e:  # network trouble reaching the new project: report clearly, don't hide it
            fail(f"Could not reach the project's API for the smoke test ({type(e).__name__}: {e}). Rerun the workflow in a few minutes.")
    if smoke_email:
        sql(ref, f"delete from auth.users where email = '{smoke_email}';")
    set_owner(ref)
    report("url", url)
    report("key", key)  # publishable key only (public by design)
    log(f"Supabase ready ✓ project {ref}")


if __name__ == "__main__":
    main()
