-- Secure first-admin bootstrap, no manual SQL.
-- The provisioning workflow stores the owner's e-mail (the Supabase account owner, or ADMIN_EMAIL) in a private
-- schema. Admin is granted ONLY when that e-mail signs in with a one-time code sent to its inbox (JWT amr = otp),
-- so knowing the address is not enough (sign-ups are auto-confirmed during testing).
create schema if not exists app_private;
revoke all on schema app_private from public, anon, authenticated;
create table if not exists app_private.settings (key text primary key, value text not null);
revoke all on app_private.settings from public, anon, authenticated;

create or replace function public.claim_owner() returns boolean
language plpgsql security definer set search_path = public, app_private as $$
declare
  owner_email text;
  my_email    text;
  proved      boolean;
begin
  select value into owner_email from app_private.settings where key = 'owner_email';
  select email into my_email from auth.users where id = auth.uid();
  proved := exists (
    select 1 from jsonb_array_elements(coalesce(auth.jwt() -> 'amr', '[]'::jsonb)) m
    where coalesce(m ->> 'method', m #>> '{}') in ('otp', 'magiclink', 'email'));
  if owner_email is null or my_email is null or not proved or lower(my_email) <> lower(owner_email) then
    return false;
  end if;
  update public.profiles set role = 'admin' where id = auth.uid();
  return true;
end $$;
revoke all on function public.claim_owner() from public, anon;
grant execute on function public.claim_owner() to authenticated;
