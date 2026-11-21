"""Environment configuration. Secrets come ONLY from env vars (see .env.example)."""
import os


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


DB_PATH = env("DB_PATH", "data/deals.db")
UPLOAD_DIR = env("UPLOAD_DIR", "data/uploads")
ADMIN_TOKEN = env("ADMIN_TOKEN")  # required for /admin APIs; admin is disabled if empty
DISABLE_SCHEDULER = env("DISABLE_SCHEDULER") == "1"

# Amazon Creators API (Amazon Associates Egypt account required)
AMAZON_CLIENT_ID = env("AMAZON_CLIENT_ID")
AMAZON_CLIENT_SECRET = env("AMAZON_CLIENT_SECRET")
AMAZON_PARTNER_TAG = env("AMAZON_PARTNER_TAG")
AMAZON_TOKEN_URL = env("AMAZON_TOKEN_URL", "https://api.amazon.co.uk/auth/o2/token")  # EU region covers EG
AMAZON_API_BASE = env("AMAZON_API_BASE", "https://creatorsapi.amazon/catalog/v1")

# Affiliate product feeds (CSV / JSON / XML). URL may contain the affiliate's token.
JUMIA_FEED_URL = env("JUMIA_FEED_URL")
NOON_FEED_URL = env("NOON_FEED_URL")
