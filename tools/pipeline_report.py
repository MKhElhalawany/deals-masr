#!/usr/bin/env python3
"""Turns the pipeline result into plain-language GitHub issues (GitHub e-mails them to the repo owner).

success → comment on the pinned "📱 آخر نسخة من التطبيق" issue with the APK link; close open failure issues
failure → open/update "⚠️ التحديث التلقائي واقف" with what failed and what to do
Never prints secrets: only public values (APK link, masked owner e-mail) reach this script.
"""
import json
import os
import subprocess

E = os.environ.get
REPO = E("GITHUB_REPOSITORY")
RELEASE_TITLE = "📱 آخر نسخة من التطبيق"
FAIL_TITLE = "⚠️ التحديث التلقائي واقف"


def gh(*args, input=None):
    r = subprocess.run(["gh", *args, "--repo", REPO], input=input, text=True, capture_output=True)
    if r.returncode != 0:
        print(r.stderr)
    return r.stdout.strip()


def ensure_label(name, color):
    gh("label", "create", name, "--color", color, "--force")


def find_issue(label):
    out = gh("issue", "list", "--label", label, "--state", "open", "--json", "number", "-q", ".[0].number")
    return out or None


def upsert_issue(label, title, body):
    n = find_issue(label)
    if n:
        gh("issue", "comment", n, "--body-file", "-", input=body)
    else:
        gh("issue", "create", "--title", title, "--label", label, "--body-file", "-", input=body)


def main():
    ensure_label("pipeline-failure", "d73a4a")
    ensure_label("app-release", "0e8a16")
    results = {"اختبارات الخادم": E("R_BACKEND"), "اختبارات أمان قاعدة البيانات": E("R_SQL"),
               "تجهيز Supabase": E("R_SUPABASE"), "بناء التطبيق": E("R_ANDROID")}
    failed = [k for k, v in results.items() if v == "failure"]
    run = E("RUN_URL")

    if failed:
        reason = E("SUPABASE_ERROR") or "تفاصيل الخطأ في صفحة التشغيل."
        body = (f"**اللي وقف:** {'، '.join(failed)}\n\n**السبب:** {reason}\n\n"
                f"هنحاول تاني تلقائياً كل ساعة. لو السبب في التوكن، اعمل توكن جديد وحطه مكان `SUPABASE_ACCESS_TOKEN`.\n\n"
                f"[تفاصيل التشغيل]({run})\n\n<sub>Failed: {', '.join(f'{k}={v}' for k, v in results.items())}</sub>")
        upsert_issue("pipeline-failure", FAIL_TITLE, body)
        print("reported failure"); return

    # success: close failure issues
    n = find_issue("pipeline-failure")
    if n:
        gh("issue", "close", n, "--comment", f"✅ اتحلت — التشغيل نجح: {run}")

    if E("CONNECTED") == "yes" and E("APK"):
        owner = E("OWNER")
        admin = ""
        if owner and E("ADMIN_READY") != "yes":
            admin = (f"\n\n**علشان تبقى أدمن (مرة واحدة):** افتح التطبيق ← «صاحب المتجر؟ ادخل بكود على الإيميل» ← "
                     f"اكتب إيميل حساب Supabase بتاعك ({owner}) ← هيوصلك كود من 6 أرقام ← اكتبه. خلاص.")
        elif not owner:
            admin = "\n\nمقدرناش نعرف إيميلك من Supabase. ضيف Secret اسمه `ADMIN_EMAIL` فيه إيميلك."
        body = (f"## ✅ النسخة الجديدة جاهزة\n\n**نزّلها من هنا:** {E('APK')}\n\n"
                f"قاعدة البيانات وتسجيل الدخول والأمان اتجهزوا واتختبروا تلقائياً.{admin}\n\n[تفاصيل التشغيل]({run})")
        upsert_issue("app-release", RELEASE_TITLE, body)
        print("reported release")
    else:
        print("built without Supabase (no token yet) — nothing to report")


if __name__ == "__main__":
    main()
