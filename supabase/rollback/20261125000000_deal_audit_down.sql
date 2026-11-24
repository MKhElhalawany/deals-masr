-- ROLLBACK for 20261125000000_deal_audit.sql: re-show deals hidden by the audit, restore the previous ingest function.
update public.deals set active = true, updated_at = now() where audit_status = 'fake' and last_checked > now() - interval '36 hours';
drop function if exists public.apply_audit(jsonb);
\i ../migrations/20261123020000_deals_v2.sql
-- the new columns are left in place (harmless); drop them manually if needed
