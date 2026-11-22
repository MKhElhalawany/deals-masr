# 24/7 deals server (Oracle Cloud Always Free) — one-time setup

All scraping (Amazon, Jumia, noon, SHEIN pricing) runs on this server, never on phones.
The app only reads results from Supabase.

## Why Oracle Cloud
Its **Always Free** tier gives a real virtual machine that runs 24/7 for free (Arm Ampere A1: up to 4 CPUs / 24 GB RAM),
enough for a headless Chrome. Other free hosts sleep or don't have enough memory for a browser.

## You do (once), about 15 minutes
1. **Create the account:** https://signup.cloud.oracle.com
   - Oracle verifies your identity and card; Always Free resources are not charged.
   - Home region: **Saudi Arabia West (Jeddah)** or **UAE East (Dubai)** (closest to Egypt), or **Germany Central (Frankfurt)**.
     It can't be changed later.
2. **Create the server:** Menu → Compute → Instances → **Create instance**
   - Image: **Canonical Ubuntu 22.04**
   - Shape: **Ampere → VM.Standard.A1.Flex**, 2 OCPUs, 12 GB memory (inside the free limit)
   - Networking: keep the defaults (public IPv4 address)
   - SSH keys: **Generate a key pair for me** → **Save private key** (a `.key` file)
   - **Create**. If it says "Out of capacity", try again later or pick another availability domain.
3. **Give GitHub the address and the key:** repo → Settings → Secrets and variables → Actions → *New repository secret*
   - `SERVER_HOST` = the instance's **Public IP address**
   - `SERVER_SSH_KEY` = the whole content of the downloaded `.key` file (open it in Notepad, copy everything)

## Then it's automatic
Within about 15 minutes the pipeline:
- installs Docker on the server
- builds the worker (Python + headless Chromium)
- puts the server-only Supabase key on it (fetched from Supabase over SSH, never stored in GitHub or the app)
- starts it with auto-restart

You'll get a GitHub e-mail saying **"سيرفر العروض: ✅ شغال"**.
Every later code change is redeployed automatically. The live status per store appears in the app under
**الإعدادات ← حالة سيرفر العروض** (admin only).

## If a store blocks the server
Stores sometimes block data-center IP addresses. The app shows "⚠️ المتجر حاجب السيرفر", and the worker backs off and retries.
The fix is a residential proxy: add a GitHub secret `PROXY_URL` (e.g. `http://user:pass@host:port`) — paid, a few dollars a month.
