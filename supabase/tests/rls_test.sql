-- RLS regression tests. Safe on a database that already has real data: everything is scoped to test ids,
-- and CI/live runs execute it inside a transaction that is rolled back.
\set ON_ERROR_STOP 1
create function pg_temp.must_fail(q text) returns void language plpgsql as $$
begin
  begin execute q; exception when others then return; end;
  raise exception 'SECURITY TEST FAILED, this should have been rejected: %', q;
end $$;
create function pg_temp.check(ok boolean, what text) returns void language plpgsql as $$
begin if not coalesce(ok, false) then raise exception 'TEST FAILED: %', what; end if; end $$;

insert into auth.users (id, email, raw_user_meta_data) values
 ('a1111111-1111-4111-8111-111111111111','rls-a@example.com','{"full_name":"Ahmed","phone":"01011111111"}'),
 ('b2222222-2222-4222-8222-222222222222','rls-b@example.com','{}'),
 ('c3333333-3333-4333-8333-333333333333','rls-admin@example.com','{}');
update public.profiles set role = 'admin' where id = 'c3333333-3333-4333-8333-333333333333';
select pg_temp.check((select count(*) from public.profiles where id in
  ('a1111111-1111-4111-8111-111111111111','b2222222-2222-4222-8222-222222222222','c3333333-3333-4333-8333-333333333333')) = 3, 'profile trigger');
select pg_temp.check((select full_name from public.profiles where id = 'a1111111-1111-4111-8111-111111111111') = 'Ahmed', 'profile metadata');
select pg_temp.check((select count(*) from public.user_settings where user_id = 'a1111111-1111-4111-8111-111111111111') = 1, 'settings trigger');

-- customer A
set role authenticated;
select set_config('request.jwt.claim.sub', 'a1111111-1111-4111-8111-111111111111', false);
insert into public.orders (id, customer_name, customer_phone, items, subtotal_egp, delivery_fee_egp, total_egp)
  values ('RLSTEST01', 'Ahmed', '01011111111', '[{"title":"Dress"}]', 1620, 130, 1750);
select pg_temp.check((select count(*) from public.orders where id = 'RLSTEST01') = 1, 'A reads own order');
select pg_temp.check((select count(*) from public.orders where user_id <> auth.uid()) = 0, 'A sees only own orders');
select pg_temp.check((select count(*) from public.profiles where id <> auth.uid()) = 0, 'A sees only own profile');
select pg_temp.must_fail($$insert into public.orders (id, user_id, customer_name, customer_phone, items, subtotal_egp, delivery_fee_egp, total_egp)
  values ('RLSTEST02', 'b2222222-2222-4222-8222-222222222222', 'Attacker', '01011111111', '[{}]', 1, 1, 2)$$);
select pg_temp.must_fail($$insert into public.orders (id, customer_name, customer_phone, items, subtotal_egp, delivery_fee_egp, total_egp, status)
  values ('RLSTEST03', 'Attacker', '01011111111', '[{}]', 1, 1, 2, 'confirmed')$$);
with u as (update public.orders set status = 'confirmed' where id = 'RLSTEST01' returning 1)
  select pg_temp.check((select count(*) from u) = 0, 'customer cannot confirm');
select pg_temp.must_fail($$update public.profiles set role = 'admin' where id = auth.uid()$$);
with u as (update public.profiles set full_name = 'Ahmed M' where id = auth.uid() returning 1)
  select pg_temp.check((select count(*) from u) = 1, 'customer can edit own name');
select pg_temp.must_fail($$insert into public.deals (id, store_id, title, url, original_price, current_price)
  values ('amazon:RLSTEST', 'amazon', 'title', 'https://x', 100, 50)$$);
with u as (update public.app_config set value = '"0"' where key = 'whatsapp_number' returning 1)
  select pg_temp.check((select count(*) from u) = 0, 'customer cannot change config');
reset role;

-- customer B cannot see A
set role authenticated;
select set_config('request.jwt.claim.sub', 'b2222222-2222-4222-8222-222222222222', false);
select pg_temp.check((select count(*) from public.orders where id = 'RLSTEST01') = 0, 'B cannot see A order');
reset role;

-- anonymous
set role anon;
select pg_temp.must_fail($$select count(*) from public.orders$$);
select pg_temp.must_fail($$select count(*) from public.profiles$$);
reset role;

-- admin
set role authenticated;
select set_config('request.jwt.claim.sub', 'c3333333-3333-4333-8333-333333333333', false);
select pg_temp.check((select count(*) from public.orders where id = 'RLSTEST01') = 1, 'admin sees customer orders');
update public.orders set status = 'confirmed' where id = 'RLSTEST01';
select pg_temp.check((select confirmed_at is not null from public.orders where id = 'RLSTEST01'), 'server stamps confirmed_at');
insert into public.deals (id, store_id, title, url, original_price, current_price) values ('amazon:RLSTEST', 'amazon', 't', 'https://x', 200, 50);
select pg_temp.check((select discount_pct from public.deals where id = 'amazon:RLSTEST') = 75.0, 'generated discount');
reset role;
select set_config('request.jwt.claim.sub', '', false);
select 'ALL RLS TESTS PASSED';
