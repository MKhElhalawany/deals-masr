-- Realtime: stream deal / notification / order / SHEIN-quote changes to connected apps (RLS still applies per user).
do $$
declare t text;
begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    create publication supabase_realtime;
  end if;
  foreach t in array array['deals', 'user_notifications', 'orders', 'shein_quotes'] loop
    if not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = t) then
      execute format('alter publication supabase_realtime add table public.%I', t);
    end if;
  end loop;
end $$;

-- Push: one row per installed app (FCM registration token). Users manage only their own tokens.
create table public.device_tokens (
  token       text primary key check (char_length(token) between 20 and 4096),
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  min_pct     int not null default 10 check (min_pct between 0 and 95),
  enabled     boolean not null default true,
  updated_at  timestamptz not null default now()
);
create index device_tokens_user on public.device_tokens (user_id);
alter table public.device_tokens enable row level security;
revoke all on public.device_tokens from anon;
create policy device_tokens_own on public.device_tokens for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
