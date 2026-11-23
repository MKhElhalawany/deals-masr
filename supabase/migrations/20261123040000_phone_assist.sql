-- Phone assist (approved by the owner on 2026-11-23): while the app is open on Wi-Fi, a phone can read ONE page
-- every ~60 s for stores that block cloud servers (Jumia). Pages come only from the server's queue; the phone
-- sends back compact product data, which the server validates before saving. Additive; rollback = drop objects.
create table if not exists public.phone_reports (
  id         bigint generated always as identity primary key,
  user_id    uuid not null references auth.users(id) on delete cascade,
  target_id  bigint references public.scan_targets(id) on delete set null,
  store      text not null,
  at         timestamptz not null default now(),
  status     text not null,
  items      int,
  saved      int
);
create index if not exists phone_reports_user_at on public.phone_reports (user_id, at desc);
alter table public.phone_reports enable row level security;
revoke all on public.phone_reports from anon, authenticated;

-- one page per phone per 45 s; only stores that block our server
create or replace function public.claim_phone_target(p_store text default 'jumia')
returns table (id bigint, url text)
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null or p_store not in ('jumia') then return; end if;
  if exists (select 1 from public.phone_reports r where r.user_id = auth.uid() and r.at > now() - interval '45 seconds') then return; end if;
  return query
  with c as (
    select t.id from public.scan_targets t where t.store = p_store
    order by (t.next_run_at > now()), t.next_run_at limit 1 for update skip locked
  ), u as (
    update public.scan_targets t set next_run_at = now() + interval '5 minutes' from c where t.id = c.id
    returning t.id, t.url
  ), log as (
    insert into public.phone_reports (user_id, target_id, store, status) select auth.uid(), u.id, p_store, 'claimed' from u
  )
  select u.id, u.url from u;
end $$;
revoke all on function public.claim_phone_target(text) from public, anon;
grant execute on function public.claim_phone_target(text) to authenticated;
