-- Deals Masr — initial schema (Supabase / Postgres)
-- Run once: Supabase Dashboard → SQL Editor → paste this file → Run.
-- Safe defaults: RLS on every table, anon role gets nothing, users only see their own rows,
-- admin rights come from profiles.role = 'admin' (set manually, see docs/SUPABASE_SETUP.md).

-- ───────────────────────────── helpers
create or replace function public.touch_updated_at() returns trigger
language plpgsql as $$
begin new.updated_at := now(); return new; end $$;

-- ───────────────────────────── profiles (1 row per auth user)
create table public.profiles (
  id          uuid primary key references auth.users(id) on delete cascade,
  full_name   text check (char_length(full_name) <= 100),
  phone       text check (char_length(phone) <= 20),
  role        text not null default 'user' check (role in ('user', 'admin')),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create trigger profiles_touch before update on public.profiles
  for each row execute function public.touch_updated_at();

-- security definer: reads profiles without going through RLS (no recursion)
create or replace function public.is_admin() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.profiles where id = auth.uid() and role = 'admin');
$$;

-- ───────────────────────────── per-user settings
create table public.user_settings (
  user_id         uuid primary key references auth.users(id) on delete cascade,
  notify_min_pct  int  not null default 10 check (notify_min_pct between 0 and 95),
  live_alerts     boolean not null default true,
  theme           text not null default 'system' check (theme in ('system', 'light', 'dark')),
  updated_at      timestamptz not null default now()
);
create trigger user_settings_touch before update on public.user_settings
  for each row execute function public.touch_updated_at();

-- create profile + settings automatically on sign-up
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, full_name, phone)
  values (new.id,
          left(nullif(new.raw_user_meta_data ->> 'full_name', ''), 100),
          left(nullif(new.raw_user_meta_data ->> 'phone', ''), 20));
  insert into public.user_settings (user_id) values (new.id);
  return new;
end $$;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

-- ───────────────────────────── catalogue: stores, deals, price history
create table public.stores (
  id       text primary key,                -- amazon | jumia | noon | talabat …
  name_ar  text not null,
  enabled  boolean not null default true
);
insert into public.stores (id, name_ar) values
  ('amazon', 'أمازون مصر'), ('jumia', 'جوميا مصر'), ('noon', 'نون مصر'), ('talabat', 'طلبات');

create table public.deals (
  id              text primary key,         -- "<store>:<external id>"
  store_id        text not null references public.stores(id),
  title           text not null check (char_length(title) <= 500),
  url             text not null,
  image_url       text,
  category        text,
  original_price  numeric(12,2) not null check (original_price > 0),
  current_price   numeric(12,2) not null check (current_price > 0 and current_price < original_price),
  discount_pct    numeric(5,1) generated always as
                    (round((original_price - current_price) / original_price * 100, 1)) stored,
  rating          numeric(2,1),
  rating_count    int,
  active          boolean not null default true,
  first_seen      timestamptz not null default now(),
  deal_since      timestamptz not null default now(),
  last_checked    timestamptz not null default now()
);
create index deals_active_discount on public.deals (active, discount_pct desc);
create index deals_store on public.deals (store_id, last_checked desc);

create table public.price_history (
  deal_id         text not null references public.deals(id) on delete cascade,
  current_price   numeric(12,2) not null,
  original_price  numeric(12,2) not null,
  checked_at      timestamptz not null default now(),
  primary key (deal_id, checked_at)
);

-- ───────────────────────────── favorites & alerts (per user)
create table public.favorites (
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  deal_id     text not null references public.deals(id) on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (user_id, deal_id)
);

create table public.alerts (
  id            bigint generated always as identity primary key,
  user_id       uuid not null default auth.uid() references auth.users(id) on delete cascade,
  query         text not null check (char_length(query) between 1 and 100),
  min_discount  int  not null default 30 check (min_discount between 0 and 95),
  stores        text[],
  max_price     numeric(12,2),
  created_at    timestamptz not null default now()
);
create index alerts_user on public.alerts (user_id);

-- ───────────────────────────── SHEIN orders
create table public.orders (
  id                text primary key check (id ~ '^[A-Z0-9]{6,20}$'),   -- generated by the app
  user_id           uuid not null default auth.uid() references auth.users(id) on delete cascade,
  customer_name     text not null check (char_length(customer_name) between 2 and 100),
  customer_phone    text not null check (customer_phone ~ '^\+?[0-9]{8,15}$'),
  address           text check (char_length(address) <= 500),
  notes             text check (char_length(notes) <= 1000),
  items             jsonb not null check (jsonb_typeof(items) = 'array'
                                          and jsonb_array_length(items) between 1 and 50),
  subtotal_egp      numeric(12,2) not null check (subtotal_egp >= 0),
  delivery_fee_egp  numeric(12,2) not null check (delivery_fee_egp >= 0),
  total_egp         numeric(12,2) not null check (total_egp >= 0),
  pricing           jsonb not null default '{}'::jsonb,
  status            text not null default 'submitted'
                    check (status in ('submitted','confirmed','purchased','shipped','delivered','cancelled')),
  confirmed_at      timestamptz,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);
create index orders_user on public.orders (user_id, created_at desc);
create index orders_status on public.orders (status, created_at desc);

-- the server (not the phone) stamps the confirmation time → the 25–35 day countdown can't be faked
create or replace function public.orders_before_update() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();
  if new.status in ('confirmed','purchased','shipped','delivered') and new.confirmed_at is null then
    new.confirmed_at := now();
  end if;
  return new;
end $$;
create trigger orders_before_update before update on public.orders
  for each row execute function public.orders_before_update();

-- ───────────────────────────── app config (pricing formula, WhatsApp number…)
create table public.app_config (
  key    text primary key,
  value  jsonb not null
);
insert into public.app_config (key, value) values
  ('shein_pricing', '{"sar_to_egp":"13.5","multiplier":"1.20","delivery_fee_egp":"130"}'),
  ('whatsapp_number', '"201050038728"'),
  ('order_days', '{"min":25,"max":35}'),
  ('min_discount_pct', '10');

-- ───────────────────────────── Row Level Security
alter table public.profiles       enable row level security;
alter table public.user_settings  enable row level security;
alter table public.stores         enable row level security;
alter table public.deals          enable row level security;
alter table public.price_history  enable row level security;
alter table public.favorites      enable row level security;
alter table public.alerts         enable row level security;
alter table public.orders         enable row level security;
alter table public.app_config     enable row level security;

-- signed-out visitors get nothing
revoke all on public.profiles, public.user_settings, public.stores, public.deals, public.price_history,
              public.favorites, public.alerts, public.orders, public.app_config from anon;

-- profiles: see/edit yourself (admin sees all); the role column is not writable by users
create policy profiles_select on public.profiles for select to authenticated
  using (id = auth.uid() or public.is_admin());
create policy profiles_update on public.profiles for update to authenticated
  using (id = auth.uid()) with check (id = auth.uid());
revoke update, insert, delete on public.profiles from authenticated;
grant update (full_name, phone) on public.profiles to authenticated;

-- settings / favorites / alerts: own rows only
create policy settings_all on public.user_settings for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy favorites_all on public.favorites for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy alerts_all on public.alerts for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- catalogue: everyone signed in reads; only admins (or the service role used by a future scraper) write
create policy stores_read on public.stores for select to authenticated using (true);
create policy stores_admin on public.stores for all to authenticated using (public.is_admin()) with check (public.is_admin());
create policy deals_read on public.deals for select to authenticated using (true);
create policy deals_admin on public.deals for all to authenticated using (public.is_admin()) with check (public.is_admin());
create policy history_read on public.price_history for select to authenticated using (true);
create policy history_admin on public.price_history for all to authenticated using (public.is_admin()) with check (public.is_admin());
create policy config_read on public.app_config for select to authenticated using (true);
create policy config_admin on public.app_config for all to authenticated using (public.is_admin()) with check (public.is_admin());

-- orders: customers create & read their own (only as "submitted"); only admins change status
create policy orders_select on public.orders for select to authenticated
  using (user_id = auth.uid() or public.is_admin());
create policy orders_insert on public.orders for insert to authenticated
  with check (user_id = auth.uid() and status = 'submitted' and confirmed_at is null);
create policy orders_admin_update on public.orders for update to authenticated
  using (public.is_admin()) with check (public.is_admin());
