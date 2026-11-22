-- ROLLBACK for 20261123020000_deals_v2.sql. Rows saved without an old price are hidden first (they cannot satisfy NOT NULL).
update public.deals set active = false where original_price is null;
drop function if exists public.deactivate_seen_without_deal(text[]);
-- restore the previous ingest_deals:
\i ../migrations/20261123010000_ingest_accepted_count.sql
drop index if exists public.deals_active_deal_pct;
alter table public.deals drop column if exists deal_pct;
alter table public.deals drop column if exists best_price;
alter table public.deals drop column if exists min_discount_pct;
-- original_price stays nullable (re-adding NOT NULL needs the hidden rows deleted first; not done automatically)
