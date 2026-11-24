-- rollback of 20261125010000_crazy_posts (apply_audit stays: the earlier version only lacked the updated_at bump)
drop table if exists public.tg_posts;
alter table public.phone_reports drop column if exists note;
