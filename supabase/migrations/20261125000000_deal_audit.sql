-- Deal audit (fake "before" prices) + categories + brand. Additive.
--  • audit_status: unchecked → ok | fake (fake = hidden, and not re-shown while the store keeps the same inflated price)
--  • typical_low/high: typical regular price range in Egypt for that kind of product (from the AI audit)
--  • category: "main/sub" from the app's fixed list (sidebar menu); brand: from the store data when available
-- Rollback: supabase/rollback/20261125000000_deal_audit_down.sql
alter table public.deals add column if not exists brand text;
alter table public.deals add column if not exists audit_status text not null default 'unchecked' check (audit_status in ('unchecked', 'ok', 'fake'));
alter table public.deals add column if not exists audit_note text;
alter table public.deals add column if not exists typical_low numeric(12,2);
alter table public.deals add column if not exists typical_high numeric(12,2);
alter table public.deals add column if not exists audited_at timestamptz;
create index if not exists deals_audit_queue on public.deals (audit_status, deal_pct desc) where active;
create index if not exists deals_category on public.deals (category) where active;

create or replace function public.ingest_deals(p_items jsonb, p_min_pct numeric default 10)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare
  ts timestamptz := clock_timestamp();
  v_events jsonb;
  v_notes jsonb;
  v_n int;
begin
  drop table if exists _src; drop table if exists _old;
  create temp table _src on commit drop as
    select distinct on (x.id) x.id, x.store, left(btrim(x.title), 500) as title, x.url, x.image_url,
           round(x.current_price, 2) as cp,
           case when x.original_price > x.current_price then round(x.original_price, 2) end as op,
           case when x.original_price is null and x.min_pct between 10 and 95 then round(x.min_pct, 1) end as minp,
           case when x.best_price > 0 and x.best_price < x.current_price then round(x.best_price, 2) end as bp,
           case when x.rating between 0 and 5 then round(x.rating, 1) end as rating, x.rating_count,
           coalesce(round((x.original_price - x.current_price) / nullif(x.original_price, 0) * 100, 1),
                    case when x.original_price is null then round(x.min_pct, 1) end) as pct
    , left(btrim(x.brand), 80) as brand, left(btrim(x.category), 80) as category
    from jsonb_to_recordset(coalesce(p_items, '[]')) as x(id text, store text, title text, url text, image_url text,
         current_price numeric, original_price numeric, min_pct numeric, best_price numeric, rating numeric, rating_count int,
         brand text, category text)
    where x.id is not null and coalesce(btrim(x.title), '') <> '' and x.url like 'https://%'
      and x.current_price > 0 and x.current_price < 10000000
      -- a real crossed-out price, OR (Amazon only) a listing that itself guarantees "X% off or more" with no old price shown
      and ((x.original_price > x.current_price and x.original_price < 10000000)
           or (x.original_price is null and x.store = 'amazon' and x.min_pct between 10 and 95))
      and x.store in (select s.id from public.stores s);
  delete from _src where pct is null or pct < p_min_pct or pct > 95;
  -- deals our audit marked as fake stay hidden while the store keeps the same inflated "before" price
  delete from _src s using public.deals d
  where d.id = s.id and d.audit_status = 'fake'
    and abs(coalesce(d.original_price, 0) - coalesce(s.op, 0)) <= coalesce(d.original_price, 0) * 0.02;
  select count(*)::int into v_n from _src;   -- accepted = deals kept after the discount filter

  create temp table _old on commit drop as
    select d.id, d.current_price, d.original_price, d.min_discount_pct, d.best_price, d.active from public.deals d join _src using (id);
  -- a known old price (e.g. verified on the product page) is kept while it is still above the current price
  update _src s set op = o.original_price, minp = null,
         pct = round((o.original_price - s.cp) / o.original_price * 100, 1)
  from _old o where s.id = o.id and s.op is null and o.original_price > s.cp;
  delete from _src where pct < p_min_pct or pct > 95;

  insert into public.deals as d (id, store_id, title, url, image_url, original_price, current_price, min_discount_pct, best_price,
                                 rating, rating_count, brand, category, active, last_checked, updated_at, deal_since)
  select s.id, s.store, s.title, s.url, s.image_url, s.op, s.cp, s.minp, s.bp, s.rating, s.rating_count, s.brand, s.category,
         true, ts, ts, ts from _src s
  on conflict (id) do update set
    title = excluded.title, url = excluded.url, image_url = coalesce(excluded.image_url, d.image_url),
    original_price = excluded.original_price, current_price = excluded.current_price,
    min_discount_pct = excluded.min_discount_pct, best_price = excluded.best_price,
    rating = coalesce(excluded.rating, d.rating), rating_count = coalesce(excluded.rating_count, d.rating_count),
    brand = coalesce(excluded.brand, d.brand), category = coalesce(d.category, excluded.category),
    -- a new "before" price must be audited again
    audit_status = case when d.original_price is distinct from excluded.original_price then 'unchecked' else d.audit_status end,
    active = true, last_checked = ts, updated_at = ts,
    deal_since = case when d.active then d.deal_since else ts end
  where not d.active or d.current_price <> excluded.current_price
     or d.original_price is distinct from excluded.original_price or d.min_discount_pct is distinct from excluded.min_discount_pct
     or d.best_price is distinct from excluded.best_price;

  -- "still listed" timestamp: refreshed at most every 6 h (expiry works on 36 h) → ~95% fewer writes
  insert into public.deal_seen as ds (deal_id, seen_at) select id, ts from _src
  on conflict (deal_id) do update set seen_at = excluded.seen_at
  where ds.seen_at < excluded.seen_at - interval '6 hours';

  insert into public.price_history (deal_id, current_price, original_price, checked_at)
  select s.id, s.cp, s.op, ts from _src s left join _old o using (id)
  where o.id is null or not o.active
     or abs(s.cp - o.current_price) >= o.current_price * 0.01      -- history only for meaningful (>= 1%) moves
     or abs(coalesce(s.op, 0) - coalesce(o.original_price, 0)) >= coalesce(o.original_price, 1) * 0.01
  on conflict do nothing;

  select coalesce(jsonb_agg(jsonb_build_object(
           'kind', case when o.id is null or not o.active then 'new' else 'drop' end,
           'id', s.id, 'store', s.store, 'title', s.title, 'url', s.url, 'image_url', s.image_url,
           'current_price', s.cp, 'original_price', s.op, 'pct', s.pct, 'old_price', o.current_price)), '[]'::jsonb)
    into v_events
  from _src s left join _old o using (id)
  where o.id is null or not o.active or s.cp < o.current_price - 0.5;

  with ev as (
    select * from jsonb_to_recordset(v_events) e(kind text, id text, store text, title text, current_price numeric,
                                                 original_price numeric, pct numeric, old_price numeric)
  ), n as (
    select a.user_id, 'alert'::text as kind, ev.id as deal_id, ev.pct as min_pct,
           format('تنبيه: %s بخصم %s%%', a.query, floor(ev.pct)) as title,
           format('%s — %s ج.م بدل %s', left(ev.title, 120), to_char(ev.current_price, 'FM999,999,990'),
                  to_char(ev.original_price, 'FM999,999,990')) as body
    from ev join public.alerts a
      on ev.pct >= a.min_discount
     and (a.stores is null or cardinality(a.stores) = 0 or ev.store = any(a.stores))
     and (a.max_price is null or ev.current_price <= a.max_price)
     and not exists (select 1 from regexp_split_to_table(lower(a.query), '\s+') w
                     where w <> '' and position(w in lower(ev.title)) = 0)
    union all
    select f.user_id, 'price_drop', ev.id, ev.pct,
           format('نزل السعر! %s ج.م', to_char(ev.current_price, 'FM999,999,990')),
           format('%s (كان %s)', left(ev.title, 120), to_char(ev.old_price, 'FM999,999,990'))
    from ev join public.favorites f on f.deal_id = ev.id
    where ev.kind = 'drop'
  ), ins as (
    insert into public.user_notifications (user_id, kind, deal_id, min_pct, title, body)
    select user_id, kind, deal_id, min_pct, title, body from n
    returning user_id, deal_id, title, body
  )
  select coalesce(jsonb_agg(to_jsonb(ins)), '[]'::jsonb) into v_notes from ins;

  return jsonb_build_object('accepted', v_n, 'events', v_events, 'notifications', v_notes);
end $$;

-- the audit job writes its verdicts in one call; fake deals are hidden at once
create or replace function public.apply_audit(p_rows jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare n_ok int; n_fake int;
begin
  with r as (
    select * from jsonb_to_recordset(coalesce(p_rows, '[]')) as x(id text, status text, note text, category text, typical_low numeric, typical_high numeric)
    where x.status in ('ok', 'fake')
  ), u as (
    update public.deals d set audit_status = r.status, audit_note = left(r.note, 200),
      category = coalesce(nullif(left(r.category, 80), ''), d.category),
      typical_low = r.typical_low, typical_high = r.typical_high, audited_at = now(),
      active = case when r.status = 'fake' then false else d.active end,
      updated_at = case when r.status = 'fake' and d.active then now() else d.updated_at end
    from r where d.id = r.id
    returning r.status
  )
  select count(*) filter (where status = 'ok'), count(*) filter (where status = 'fake') into n_ok, n_fake from u;
  return jsonb_build_object('ok', n_ok, 'fake', n_fake);
end $$;
revoke all on function public.apply_audit(jsonb) from public, anon, authenticated;
grant execute on function public.apply_audit(jsonb) to service_role;
