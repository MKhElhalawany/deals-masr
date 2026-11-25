-- Short technical errors from the in-app SHEIN browser (page load errors, renderer crashes, script errors), so problems
-- seen on real phones can be fixed. No page content, no personal data. Written only by the api function; nobody reads via API.
create table if not exists public.client_logs (
  id       bigint generated always as identity primary key,
  user_id  uuid,
  at       timestamptz not null default now(),
  kind     text not null,
  message  text,
  url      text,
  app      text
);
create index if not exists client_logs_at on public.client_logs (at desc);
alter table public.client_logs enable row level security;
revoke all on public.client_logs from anon, authenticated;
