-- Discovery engine: a persistent scan queue so the free scanner sweeps every category of each store (only
-- discounted items are saved) instead of a fixed list. Additive only: new tables/functions + one new store row.
-- Rollback: supabase/rollback/20261123000000_scan_queue_down.sql

insert into public.stores (id, name_ar) values ('townteam', 'تاون تيم') on conflict (id) do nothing;

create table if not exists public.scan_targets (
  id            bigint generated always as identity primary key,
  store         text not null references public.stores(id),
  url           text not null check (url like 'https://%' and char_length(url) <= 1000),
  kind          text not null check (kind in ('seed', 'category', 'page', 'keyword')),
  label         text,
  depth         int  not null default 0,
  next_run_at   timestamptz not null default now(),
  interval_sec  int  not null default 300,
  min_interval  int  not null default 60,
  last_run_at   timestamptz,
  runs          int  not null default 0,
  last_items    int,
  last_deals    int,
  last_new      int,
  yield_ema     real not null default 0,
  fingerprint   text,
  fails         int  not null default 0,
  last_status   text,
  created_at    timestamptz not null default now(),
  unique (store, url)
);
create index if not exists scan_targets_due on public.scan_targets (store, next_run_at);
alter table public.scan_targets enable row level security;
revoke all on public.scan_targets from anon, authenticated;

create table if not exists public.scan_log (
  id         bigint generated always as identity primary key,
  target_id  bigint references public.scan_targets(id) on delete cascade,
  store      text not null,
  at         timestamptz not null default now(),
  status     text not null,
  items      int, deals int, new_events int, ms int
);
create index if not exists scan_log_at on public.scan_log (at);
alter table public.scan_log enable row level security;
revoke all on public.scan_log from anon, authenticated;

create table if not exists public.robots_cache (
  store      text primary key references public.stores(id),
  body       text not null,
  fetched_at timestamptz not null default now()
);
alter table public.robots_cache enable row level security;
revoke all on public.robots_cache from anon, authenticated;

-- Hand out the next due targets of one store (never two runs on the same target: row locks + a 5-min lease).
-- If nothing is due, the earliest target is used so no scheduled slot is wasted.
create or replace function public.claim_targets(p_store text, p_n int default 1)
returns table (id bigint, url text, kind text, label text, depth int, fingerprint text)
language plpgsql security definer set search_path = public as $$
begin
  return query
  with c as (
    select t.id from public.scan_targets t
    where t.store = p_store
    order by (t.next_run_at > now()), t.next_run_at
    limit greatest(1, least(p_n, 5))
    for update skip locked
  )
  update public.scan_targets t set next_run_at = now() + interval '5 minutes'
  from c where t.id = c.id
  returning t.id, t.url, t.kind, t.label, t.depth, t.fingerprint;
end $$;

-- Record a scan result and schedule the next visit: pages that produced new deals/price drops come back
-- sooner (down to min_interval), unchanged pages back off (x2, max 6 h), failures back off (max 1 h).
create or replace function public.finish_target(p_id bigint, p_status text, p_items int, p_deals int, p_new int,
                                                p_fingerprint text, p_ms int default null)
returns int language plpgsql security definer set search_path = public as $$
declare t public.scan_targets; nxt int;
begin
  select * into t from public.scan_targets where id = p_id for update;
  if not found then return null; end if;
  if p_status <> 'ok' then
    nxt := least(3600, 120 * (2 ^ least(t.fails, 5)))::int;
    update public.scan_targets set fails = fails + 1, last_status = p_status, last_run_at = now(), runs = runs + 1,
      next_run_at = now() + make_interval(secs => nxt) where id = p_id;
  else
    nxt := case
      when coalesce(p_new, 0) > 0 then greatest(t.min_interval, t.interval_sec / 2)
      when p_fingerprint is not distinct from t.fingerprint then least(21600, t.interval_sec * 2)
      when coalesce(p_deals, 0) > 0 then least(21600, (t.interval_sec * 1.25)::int)
      else least(21600, t.interval_sec * 2) end;
    update public.scan_targets set fails = 0, last_status = 'ok', last_run_at = now(), runs = runs + 1,
      last_items = p_items, last_deals = p_deals, last_new = p_new, fingerprint = p_fingerprint, interval_sec = nxt,
      yield_ema = 0.7 * yield_ema + 0.3 * (coalesce(p_new, 0) + 0.1 * coalesce(p_deals, 0)),
      next_run_at = now() + make_interval(secs => nxt) where id = p_id;
  end if;
  insert into public.scan_log (target_id, store, status, items, deals, new_events, ms)
  values (p_id, t.store, p_status, p_items, p_deals, p_new, p_ms);
  delete from public.scan_log where at < now() - interval '7 days' and id in
    (select id from public.scan_log where at < now() - interval '7 days' limit 200);
  return nxt;
end $$;

-- Newly discovered links (categories, next pages). New targets are due immediately; at most 3000 per store.
create or replace function public.add_targets(p_store text, p_rows jsonb)
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  if (select count(*) from public.scan_targets where store = p_store) >= 3000 then return 0; end if;
  insert into public.scan_targets (store, url, kind, label, depth, interval_sec, min_interval)
  select p_store, r.url, coalesce(r.kind, 'category'), left(r.label, 120), coalesce(r.depth, 0), 300, 60
  from jsonb_to_recordset(coalesce(p_rows, '[]')) as r(url text, kind text, label text, depth int)
  where r.url like 'https://%' and char_length(r.url) <= 1000
  limit 100
  on conflict (store, url) do nothing;
  get diagnostics n = row_count;
  return n;
end $$;

revoke all on function public.claim_targets(text, int) from public, anon, authenticated;
revoke all on function public.finish_target(bigint, text, int, int, int, text, int) from public, anon, authenticated;
revoke all on function public.add_targets(text, jsonb) from public, anon, authenticated;
grant execute on function public.claim_targets(text, int) to service_role;
grant execute on function public.finish_target(bigint, text, int, int, int, text, int) to service_role;
grant execute on function public.add_targets(text, jsonb) to service_role;
