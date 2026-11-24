-- 1) Posts of the owner's public Telegram channel (t.me/s/<channel> preview pages) → "عروض مجنونة" section
create table if not exists public.tg_posts (
  channel    text not null,
  post_id    bigint not null,
  text       text not null default '',
  url        text,
  image_url  text,
  price      numeric(12,2),
  old_price  numeric(12,2),
  posted_at  timestamptz,
  created_at timestamptz not null default now(),
  primary key (channel, post_id)
);
create index if not exists tg_posts_recent on public.tg_posts (posted_at desc nulls last);
alter table public.tg_posts enable row level security;
revoke all on public.tg_posts from anon, authenticated;
grant select on public.tg_posts to authenticated;
drop policy if exists tg_posts_read on public.tg_posts;
create policy tg_posts_read on public.tg_posts for select to authenticated using (true);

-- 2) phones report why a page gave no products (short markers only, no page content)
alter table public.phone_reports add column if not exists note text;

-- 3) audit verdicts must reach phones through the incremental sync (category changes bump updated_at)
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
      updated_at = case when (r.status = 'fake' and d.active)
                          or d.category is distinct from coalesce(nullif(left(r.category, 80), ''), d.category)
                        then now() else d.updated_at end
    from r where d.id = r.id
    returning r.status
  )
  select count(*) filter (where status = 'ok'), count(*) filter (where status = 'fake') into n_ok, n_fake from u;
  return jsonb_build_object('ok', n_ok, 'fake', n_fake);
end $$;
revoke all on function public.apply_audit(jsonb) from public, anon, authenticated;
grant execute on function public.apply_audit(jsonb) to service_role;
