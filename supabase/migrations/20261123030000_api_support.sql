-- Support tables for the app API (search translation cache, "يستاهل؟" verdict cache, assistant daily usage).
-- Server-only (RLS on, no client access). Additive. Rollback: drop the three tables.
create table if not exists public.query_translations (
  q          text primary key check (char_length(q) <= 200),
  en         text not null check (char_length(en) <= 300),
  created_at timestamptz not null default now()
);
create table if not exists public.deal_verdicts (
  deal_id    text not null references public.deals(id) on delete cascade,
  price      numeric(12,2) not null,
  result     jsonb not null,
  created_at timestamptz not null default now(),
  primary key (deal_id, price)
);
create table if not exists public.assistant_usage (
  user_id uuid not null references auth.users(id) on delete cascade,
  day     date not null default current_date,
  n       int  not null default 0,
  primary key (user_id, day)
);
alter table public.query_translations enable row level security;
alter table public.deal_verdicts enable row level security;
alter table public.assistant_usage enable row level security;
revoke all on public.query_translations, public.deal_verdicts, public.assistant_usage from anon, authenticated;

-- atomic per-user daily counter; returns the new count
create or replace function public.bump_assistant_usage(p_user uuid)
returns int language sql security definer set search_path = public as $$
  insert into public.assistant_usage (user_id, day, n) values (p_user, current_date, 1)
  on conflict (user_id, day) do update set n = assistant_usage.n + 1
  returning n;
$$;
revoke all on function public.bump_assistant_usage(uuid) from public, anon, authenticated;
grant execute on function public.bump_assistant_usage(uuid) to service_role;
