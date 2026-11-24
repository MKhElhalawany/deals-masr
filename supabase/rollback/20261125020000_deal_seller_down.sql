-- rollback: re-run 20261125000000_deal_audit.sql's ingest_deals, then:
alter table public.deals drop column if exists seller;
