-- RLS regression tests (run against a throwaway Postgres in CI after supabase_stub.sql + migrations).
\set ON_ERROR_STOP 1
create function pg_temp.must_fail(q text) returns void language plpgsql as $$
begin
  begin execute q; exception when others then return; end;
  raise exception 'SECURITY TEST FAILED, this should have been rejected: %', q;
end $$;
create function pg_temp.check(ok boolean, what text) returns void language plpgsql as $$
begin if not ok then raise exception 'TEST FAILED: %', what; end if; end $$;

insert into auth.users (id, email, raw_user_meta_data) values
 ('11111111-1111-1111-1111-111111111111','a@x.com','{"full_name":"Ahmed","phone":"01011111111"}'),
 ('22222222-2222-2222-2222-222222222222','b@x.com','{}'),
 ('33333333-3333-3333-3333-333333333333','admin@x.com','{}');
update public.profiles set role = 'admin' where id = '33333333-3333-3333-3333-333333333333';
select pg_temp.check((select count(*) from public.profiles) = 3, 'profile trigger');
select pg_temp.check((select full_name from public.profiles where id = '11111111-1111-1111-1111-111111111111') = 'Ahmed', 'profile metadata');

-- customer A
set role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', false);
insert into public.orders (id, customer_name, customer_phone, items, subtotal_egp, delivery_fee_egp, total_egp)
  values ('ORDER0001', 'Ahmed', '01011111111', '[{"title":"Dress"}]', 1620, 130, 1750);
select pg_temp.check((select count(*) from public.orders) = 1, 'A reads own order');
select pg_temp.must_fail($$insert into public.orders (id, user_id, customer_name, customer_phone, items, subtotal_egp, delivery_fee_egp, total_egp)
  values ('ORDER0002', '22222222-2222-2222-2222-222222222222', 'Attacker', '01011111111', '[{}]', 1, 1, 2)$$);
select pg_temp.must_fail($$insert into public.orders (id, customer_name, customer_phone, items, subtotal_egp, delivery_fee_egp, total_egp, status)
  values ('ORDER0003', 'Attacker', '01011111111', '[{}]', 1, 1, 2, 'confirmed')$$);
update public.orders set status = 'confirmed' where id = 'ORDER0001';
select pg_temp.check((select status from public.orders where id = 'ORDER0001') = 'submitted', 'customer cannot confirm');
select pg_temp.must_fail($$update public.profiles set role = 'admin' where id = auth.uid()$$);
update public.profiles set full_name = 'Ahmed M' where id = auth.uid();
select pg_temp.must_fail($$insert into public.deals (id, store_id, title, url, original_price, current_price) values ('amazon:1','amazon','t','u',100,50)$$);
update public.app_config set value = '"0"' where key = 'whatsapp_number';
select pg_temp.check((select count(*) from public.profiles) = 1, 'A sees only own profile');
reset role;
select pg_temp.check((select value #>> '{}' from public.app_config where key = 'whatsapp_number') = '201050038728', 'config untouched');
select pg_temp.check((select role from public.profiles where id = '11111111-1111-1111-1111-111111111111') = 'user', 'role untouched');

-- customer B cannot see A
set role authenticated;
select set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', false);
select pg_temp.check((select count(*) from public.orders) = 0, 'B cannot see A orders');
reset role;

-- anonymous
set role anon;
select pg_temp.must_fail($$select count(*) from public.orders$$);
reset role;

-- admin
set role authenticated;
select set_config('request.jwt.claim.sub', '33333333-3333-3333-3333-333333333333', false);
select pg_temp.check((select count(*) from public.orders) = 1, 'admin sees all orders');
update public.orders set status = 'confirmed' where id = 'ORDER0001';
select pg_temp.check((select confirmed_at is not null from public.orders where id = 'ORDER0001'), 'server stamps confirmed_at');
insert into public.deals (id, store_id, title, url, original_price, current_price) values ('amazon:2','amazon','t','u',200,50);
select pg_temp.check((select discount_pct from public.deals where id = 'amazon:2') = 75.0, 'generated discount');
reset role;
select 'ALL RLS TESTS PASSED';
