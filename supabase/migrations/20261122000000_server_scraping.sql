-- Server-side scraping: the 24/7 worker (service role) writes deals, status, SHEIN prices and user notifications.
-- Phones only read. Users can only *request* work (search / SHEIN price) through rate-limited queues.

alter table public.deals add column if not exists updated_at timestamptz not null default now();
alter table public.deals add column if not exists rating_count_text text;
create index if not exists deals_updated on public.deals (updated_at desc);
create index if not exists deals_title_trgm_fallback on public.deals (lower(title));

-- live status of every store, shown in the app's admin screen
create table public.scraper_status (
  store       text primary key,
  ok          boolean not null default false,
  message     text,
  deals_seen  int not null default 0,
  last_run    timestamptz not null default now()
);
alter table public.scraper_status enable row level security;
revoke all on public.scraper_status from anon;
create policy scraper_status_read on public.scraper_status for select to authenticated using (true);

-- user asks the server to search the stores now ("air fryer" etc.)
create table public.search_requests (
  id          bigint generated always as identity primary key,
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  query       text not null check (char_length(query) between 2 and 80),
  stores      text[],
  status      text not null default 'pending' check (status in ('pending','running','done','failed')),
  message     text,
  created_at  timestamptz not null default now(),
  done_at     timestamptz
);
create index search_requests_pending on public.search_requests (status, created_at);
alter table public.search_requests enable row level security;
revoke all on public.search_requests from anon;
create policy search_requests_own_read on public.search_requests for select to authenticated using (user_id = auth.uid());
create policy search_requests_insert on public.search_requests for insert to authenticated
  with check (user_id = auth.uid() and status = 'pending');

-- user sends a SHEIN link; the server prices it on SHEIN Saudi (highest retail price across sizes)
create table public.shein_quotes (
  id          bigint generated always as identity primary key,
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  url         text not null check (char_length(url) between 10 and 2000 and url ~* '^https?://'),
  kind        text not null default 'product' check (kind in ('product','bag')),
  status      text not null default 'pending' check (status in ('pending','running','priced','failed')),
  goods_id    text,
  title       text,
  image_url   text,
  retail_sar  numeric(12,2),
  items       jsonb,                      -- bag: [{goods_id,title,image_url,retail_sar,url}]
  error       text,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index shein_quotes_pending on public.shein_quotes (status, created_at);
alter table public.shein_quotes enable row level security;
revoke all on public.shein_quotes from anon;
create policy shein_quotes_own_read on public.shein_quotes for select to authenticated using (user_id = auth.uid() or public.is_admin());
create policy shein_quotes_insert on public.shein_quotes for insert to authenticated
  with check (user_id = auth.uid() and status = 'pending' and retail_sar is null and items is null);

-- abuse guard: max 30 queue requests per user per hour (each queue)
create or replace function public.limit_requests() returns trigger
language plpgsql security definer set search_path = public as $$
declare n int;
begin
  execute format('select count(*) from public.%I where user_id = $1 and created_at > now() - interval ''1 hour''', tg_table_name)
    into n using new.user_id;
  if n >= 30 then raise exception 'rate limit: too many requests, try again later'; end if;
  return new;
end $$;
create trigger search_requests_limit before insert on public.search_requests for each row execute function public.limit_requests();
create trigger shein_quotes_limit before insert on public.shein_quotes for each row execute function public.limit_requests();

-- per-user notifications written by the server (alerts matched, favourite price drops)
create table public.user_notifications (
  id          bigint generated always as identity primary key,
  user_id     uuid not null references auth.users(id) on delete cascade,
  kind        text not null,
  title       text not null,
  body        text not null,
  deal_id     text references public.deals(id) on delete set null,
  min_pct     numeric(5,1),
  created_at  timestamptz not null default now(),
  seen        boolean not null default false
);
create index user_notifications_user on public.user_notifications (user_id, created_at desc);
alter table public.user_notifications enable row level security;
revoke all on public.user_notifications from anon;
create policy notif_read on public.user_notifications for select to authenticated using (user_id = auth.uid());
create policy notif_mark_seen on public.user_notifications for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
revoke update on public.user_notifications from authenticated;
grant update (seen) on public.user_notifications to authenticated;

-- keyword list the worker cycles through (editable by admin)
insert into public.app_config (key, value) values
  ('scrape_keywords', '["air fryer","smart watch","headphones","laptop","mobile phone","perfume","vacuum cleaner","blender","coffee maker","television","sneakers","power bank"]'),
  ('scrape_stores', '["amazon","jumia","noon"]')
on conflict (key) do nothing;
