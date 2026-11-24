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

-- server-scraping queues: users can request work but never write results
set role authenticated;
select set_config('request.jwt.claim.sub', 'a1111111-1111-4111-8111-111111111111', false);
insert into public.search_requests (query) values ('air fryer');
insert into public.shein_quotes (url) values ('https://sa.shein.com/x-p-123456.html');
select pg_temp.must_fail($$insert into public.shein_quotes (url, status, retail_sar) values ('https://sa.shein.com/x-p-1.html', 'priced', 1)$$);
select pg_temp.must_fail($$insert into public.search_requests (query, user_id) values ('tv', 'b2222222-2222-4222-8222-222222222222')$$);
with u as (update public.shein_quotes set retail_sar = 1, status = 'priced' returning 1)
  select pg_temp.check((select count(*) from u) = 0, 'user cannot price own SHEIN quote');
select pg_temp.must_fail($$insert into public.scraper_status (store) values ('amazon')$$);
select pg_temp.must_fail($$insert into public.user_notifications (user_id, kind, title, body) values (auth.uid(), 'x', 'x', 'x')$$);
reset role;
set role authenticated;
select set_config('request.jwt.claim.sub', 'b2222222-2222-4222-8222-222222222222', false);
select pg_temp.check((select count(*) from public.shein_quotes) = 0, 'B cannot see A SHEIN quotes');
select pg_temp.check((select count(*) from public.search_requests) = 0, 'B cannot see A searches');
reset role;

-- push tokens: own rows only
set role authenticated;
select set_config('request.jwt.claim.sub', 'a1111111-1111-4111-8111-111111111111', false);
insert into public.device_tokens (token) values ('token-of-user-a-0123456789');
select pg_temp.must_fail($$insert into public.device_tokens (token, user_id) values ('token-forged-0123456789', 'b2222222-2222-4222-8222-222222222222')$$);
select set_config('request.jwt.claim.sub', 'b2222222-2222-4222-8222-222222222222', false);
select pg_temp.check((select count(*) from public.device_tokens) = 0, 'B cannot read A push tokens');
with u as (update public.device_tokens set enabled = false returning 1) select pg_temp.check((select count(*) from u) = 0, 'B cannot change A tokens');
reset role;

-- owner bootstrap: only the configured e-mail, only after a one-time-code login
insert into app_private.settings (key, value) values ('owner_email', 'rls-b@example.com')
  on conflict (key) do update set value = excluded.value;
set role authenticated;
select set_config('request.jwt.claim.sub', 'b2222222-2222-4222-8222-222222222222', false);
select set_config('request.jwt.claims', '{"amr":[{"method":"password","timestamp":1}]}', false);
select pg_temp.check(not public.claim_owner(), 'owner e-mail with password login must NOT become admin');
select set_config('request.jwt.claim.sub', 'a1111111-1111-4111-8111-111111111111', false);
select set_config('request.jwt.claims', '{"amr":[{"method":"otp","timestamp":1}]}', false);
select pg_temp.check(not public.claim_owner(), 'other e-mail with code login must NOT become admin');
select pg_temp.must_fail($$select * from app_private.settings$$);
select set_config('request.jwt.claim.sub', 'b2222222-2222-4222-8222-222222222222', false);
select pg_temp.check(public.claim_owner(), 'owner e-mail with code login becomes admin');
select pg_temp.check(public.is_admin(), 'owner is admin now');
reset role;
set role anon;
select pg_temp.must_fail($$select public.claim_owner()$$);
reset role;
select set_config('request.jwt.claim.sub', '', false);
select set_config('request.jwt.claims', '', false);

-- deal ingestion: server-only, writes only real changes, creates alert/favourite notifications
set role authenticated;
select set_config('request.jwt.claim.sub', 'a1111111-1111-4111-8111-111111111111', false);
select pg_temp.must_fail($$select public.ingest_deals('[]'::jsonb, 10)$$);
select pg_temp.must_fail($$select public.expire_stale_deals(36)$$);
insert into public.alerts (query, min_discount) values ('rlsprobe phone', 20);
reset role;
set role anon;
select pg_temp.must_fail($$select public.ingest_deals('[]'::jsonb, 10)$$);
reset role;
grant usage on schema public to service_role;
set role service_role;
select pg_temp.check((public.ingest_deals('[{"id":"amazon:rls-ing-1","store":"amazon","title":"RLSPROBE Phone X","url":"https://x/1","current_price":700,"original_price":1000},
  {"id":"amazon:rls-ing-2","store":"amazon","title":"cheap thing","url":"https://x/2","current_price":99,"original_price":100},
  {"id":"evil:rls-ing-3","store":"evil","title":"bad store","url":"https://x/3","current_price":1,"original_price":100},
  {"id":"amazon:rls-ing-4","store":"amazon","title":"no https","url":"javascript:x","current_price":1,"original_price":100}]'::jsonb, 10)->>'accepted')::int = 1,
  'ingest keeps only valid deals ≥ min discount from known stores');
reset role;
select pg_temp.check((select count(*) from public.user_notifications where user_id = 'a1111111-1111-4111-8111-111111111111' and deal_id = 'amazon:rls-ing-1' and kind = 'alert') = 1, 'alert match notifies the user');
update public.deals set updated_at = '2000-01-01' where id = 'amazon:rls-ing-1';
set role service_role;
select pg_temp.check(jsonb_array_length(public.ingest_deals('[{"id":"amazon:rls-ing-1","store":"amazon","title":"RLSPROBE Phone X","url":"https://x/1","current_price":700,"original_price":1000}]'::jsonb, 10)->'events') = 0, 'unchanged deal → no event');
reset role;
select pg_temp.check((select updated_at from public.deals where id = 'amazon:rls-ing-1') = '2000-01-01', 'unchanged deal row is NOT rewritten (no Realtime noise)');
insert into public.favorites (user_id, deal_id) values ('b2222222-2222-4222-8222-222222222222', 'amazon:rls-ing-1');
set role service_role;
select pg_temp.check((public.ingest_deals('[{"id":"amazon:rls-ing-1","store":"amazon","title":"RLSPROBE Phone X","url":"https://x/1","current_price":600,"original_price":1000}]'::jsonb, 10)->'events'->0->>'kind') = 'drop', 'price drop detected');
reset role;
select pg_temp.check((select count(*) from public.user_notifications where user_id = 'b2222222-2222-4222-8222-222222222222' and kind = 'price_drop') = 1, 'favourite price drop notifies');
select pg_temp.check((select count(*) from public.price_history where deal_id = 'amazon:rls-ing-1') = 2, 'price history only on changes');
-- Phase 0: deal_seen throttled to 6 h, history only on >= 1% moves, compaction keeps one row per day
update public.deal_seen set seen_at = '2001-01-01' where deal_id = 'amazon:rls-ing-1';
set role service_role;
select public.ingest_deals('[{"id":"amazon:rls-ing-1","store":"amazon","title":"RLSPROBE Phone X","url":"https://x/1","current_price":600,"original_price":1000}]'::jsonb, 10);
reset role;
select pg_temp.check((select seen_at from public.deal_seen where deal_id = 'amazon:rls-ing-1') > now() - interval '1 hour', 'stale deal_seen (> 6 h) is refreshed');
update public.deal_seen set seen_at = now() - interval '1 hour' where deal_id = 'amazon:rls-ing-1';
create temp table _seen_before as select seen_at from public.deal_seen where deal_id = 'amazon:rls-ing-1';
set role service_role;
select public.ingest_deals('[{"id":"amazon:rls-ing-1","store":"amazon","title":"RLSPROBE Phone X","url":"https://x/1","current_price":600,"original_price":1000}]'::jsonb, 10);
reset role;
select pg_temp.check((select seen_at from public.deal_seen where deal_id = 'amazon:rls-ing-1') = (select seen_at from _seen_before), 'fresh deal_seen (< 6 h) is NOT rewritten');
set role service_role;
select public.ingest_deals('[{"id":"amazon:rls-ing-1","store":"amazon","title":"RLSPROBE Phone X","url":"https://x/1","current_price":597,"original_price":1000}]'::jsonb, 10);
reset role;
select pg_temp.check((select current_price from public.deals where id = 'amazon:rls-ing-1') = 597, 'deal keeps the exact new price');
select pg_temp.check((select count(*) from public.price_history where deal_id = 'amazon:rls-ing-1') = 2, 'a 0.5% move adds no history row');
set role service_role;
select public.ingest_deals('[{"id":"amazon:rls-ing-1","store":"amazon","title":"RLSPROBE Phone X","url":"https://x/1","current_price":560,"original_price":1000}]'::jsonb, 10);
reset role;
select pg_temp.check((select count(*) from public.price_history where deal_id = 'amazon:rls-ing-1') = 3, 'a 6% move adds a history row');
insert into public.price_history (deal_id, current_price, original_price, checked_at) values
  ('amazon:rls-ing-1', 650, 1000, '2020-01-05 08:00+02'), ('amazon:rls-ing-1', 640, 1000, '2020-01-05 12:00+02'),
  ('amazon:rls-ing-1', 660, 1000, '2020-01-05 20:00+02'), ('amazon:rls-ing-1', 630, 1000, '2020-01-06 09:00+02');
set role authenticated;
select pg_temp.must_fail($$select public.compact_price_history(90)$$);
reset role;
set role service_role;
select pg_temp.check(public.compact_price_history(90) = 2, 'compaction removes 2 of the 3 rows of an old day');
reset role;
select pg_temp.check((select current_price from public.price_history where deal_id = 'amazon:rls-ing-1' and checked_at::date = '2020-01-05') = 640, 'compaction keeps the day''s lowest price');
select pg_temp.check((select count(*) from public.price_history where deal_id = 'amazon:rls-ing-1' and checked_at > now() - interval '1 day') = 3, 'recent history untouched by compaction');
-- deals v2: Amazon "X% off or more" without an old price, coupon price, immediate removal when the discount ends
set role service_role;
select pg_temp.check((public.ingest_deals('[{"id":"amazon:rls-ing-5","store":"amazon","title":"No strike","url":"https://x/5","current_price":745,"min_pct":30},
  {"id":"noon:rls-ing-6","store":"noon","title":"No strike noon","url":"https://x/6","current_price":100,"min_pct":30},
  {"id":"noon:rls-ing-7","store":"noon","title":"Coupon","url":"https://x/7","current_price":996,"original_price":2699,"best_price":697.2}]'::jsonb, 10)->>'accepted')::int = 2,
  'Amazon min-% deal and coupon deal accepted; non-Amazon without old price rejected');
reset role;
select pg_temp.check((select original_price is null and deal_pct = 30 from public.deals where id = 'amazon:rls-ing-5'), 'no-old-price deal stored with guaranteed % only');
select pg_temp.check((select best_price = 697.2 and current_price = 996 and deal_pct = 63.1 from public.deals where id = 'noon:rls-ing-7'), 'coupon price kept as a note, deal price stays 996');
update public.deals set original_price = 1200, min_discount_pct = null where id = 'amazon:rls-ing-5';
set role service_role;
select public.ingest_deals('[{"id":"amazon:rls-ing-5","store":"amazon","title":"No strike","url":"https://x/5","current_price":745,"min_pct":30}]'::jsonb, 10);
reset role;
select pg_temp.check((select original_price = 1200 from public.deals where id = 'amazon:rls-ing-5'), 'a verified old price is not erased by a listing without one');
set role authenticated;
select pg_temp.must_fail($$select public.deactivate_seen_without_deal(array['noon:rls-ing-7'])$$);
reset role;
set role service_role;
select pg_temp.check(public.deactivate_seen_without_deal(array['noon:rls-ing-7']) = 1, 'deal seen without discount is removed');
reset role;
delete from public.deals where id like 'noon:rls-ing-%';
-- deal audit: a fake "before" price hides the deal and keeps it hidden while the store keeps that price
set role service_role;
select public.ingest_deals('[{"id":"noon:rls-fake-1","store":"noon","title":"Toothpaste 100ml","url":"https://x/f","current_price":60,"original_price":1000,"brand":"X","category":"جمال وعناية/عناية شخصية"}]'::jsonb, 10);
select pg_temp.check((public.apply_audit('[{"id":"noon:rls-fake-1","status":"fake","note":"سعر قبل الخصم مبالغ فيه","typical_low":25,"typical_high":60}]'::jsonb)->>'fake')::int = 1, 'audit marks fake');
reset role;
select pg_temp.check((select not active and audit_status = 'fake' and brand = 'X' from public.deals where id = 'noon:rls-fake-1'), 'fake deal hidden');
set role service_role;
select public.ingest_deals('[{"id":"noon:rls-fake-1","store":"noon","title":"Toothpaste 100ml","url":"https://x/f","current_price":58,"original_price":1000}]'::jsonb, 10);
reset role;
select pg_temp.check((select not active from public.deals where id = 'noon:rls-fake-1'), 'same inflated before-price stays hidden');
set role service_role;
select public.ingest_deals('[{"id":"noon:rls-fake-1","store":"noon","title":"Toothpaste 100ml","url":"https://x/f","current_price":40,"original_price":60}]'::jsonb, 10);
reset role;
select pg_temp.check((select active and audit_status = 'unchecked' from public.deals where id = 'noon:rls-fake-1'), 'new before-price → shown and audited again');
set role authenticated;
select pg_temp.must_fail($$select public.apply_audit('[]'::jsonb)$$);
reset role;
delete from public.deals where id = 'noon:rls-fake-1';
delete from public.deals where id like 'amazon:rls-ing-%';
delete from public.alerts where query = 'rlsprobe phone';

-- discovery queue: server-only, claims due targets, adaptive revisit timing
set role authenticated;
select pg_temp.must_fail($$select * from public.scan_targets$$);
select pg_temp.must_fail($$select * from public.claim_targets('amazon', 1)$$);
select pg_temp.must_fail($$select public.add_targets('amazon', '[]'::jsonb)$$);
reset role;
insert into public.stores (id, name_ar) values ('rlstest', 'test store') on conflict do nothing;   -- isolated from live targets
set role service_role;
select pg_temp.check(public.add_targets('rlstest', '[{"url":"https://townteam.com/rls-a","kind":"category"},{"url":"https://townteam.com/rls-b","kind":"category"},{"url":"javascript:x"},{"url":"https://townteam.com/rls-a"}]'::jsonb) = 2, 'add_targets keeps valid, unique URLs');
reset role;
update public.scan_targets set next_run_at = now() + interval '1 hour' where url = 'https://townteam.com/rls-b';
create temp table _claimed as select * from public.claim_targets('rlstest', 1);
select pg_temp.check((select url from _claimed) = 'https://townteam.com/rls-a', 'due target is claimed first');
select pg_temp.check((select next_run_at > now() + interval '4 minutes' from public.scan_targets where url = 'https://townteam.com/rls-a'), 'claimed target is leased');
select public.finish_target((select id from _claimed), 'ok', 40, 12, 3, 'fp1', 900);
select pg_temp.check((select interval_sec from public.scan_targets where url = 'https://townteam.com/rls-a') = 150, 'new deals → revisit sooner (300 → 150 s)');
select public.finish_target((select id from _claimed), 'ok', 40, 12, 0, 'fp1', 900);
select pg_temp.check((select interval_sec from public.scan_targets where url = 'https://townteam.com/rls-a') = 300, 'unchanged page → back off (x2)');
select public.finish_target((select id from _claimed), 'blocked', null, null, null, null, 100);
select pg_temp.check((select fails from public.scan_targets where url = 'https://townteam.com/rls-a') = 1, 'failure counted, backoff applied');
select pg_temp.check((select count(*) from public.scan_log where target_id = (select id from _claimed)) = 3, 'every scan logged');
delete from public.scan_targets where store = 'rlstest';
delete from public.stores where id = 'rlstest';

-- phone assist: signed-in phones claim one Jumia page per 45 s; nothing else
insert into public.scan_targets (store, url, kind) values ('jumia', 'https://www.jumia.com.eg/rls-phone-test/', 'seed');
update public.scan_targets set next_run_at = now() - interval '10 years' where url = 'https://www.jumia.com.eg/rls-phone-test/';
set role anon;
select pg_temp.must_fail($$select * from public.claim_phone_target('jumia')$$);
reset role;
set role authenticated;
select set_config('request.jwt.claim.sub', 'a1111111-1111-4111-8111-111111111111', false);
select pg_temp.check((select count(*) from public.claim_phone_target('amazon')) = 0, 'phones only help stores that block the server');
select pg_temp.check((select url from public.claim_phone_target('jumia')) = 'https://www.jumia.com.eg/rls-phone-test/', 'phone claims a due Jumia page');
select pg_temp.check((select count(*) from public.claim_phone_target('jumia')) = 0, 'one page per phone per 45 s');
select pg_temp.must_fail($$select * from public.phone_reports$$);
reset role;
delete from public.phone_reports where user_id = 'a1111111-1111-4111-8111-111111111111';
delete from public.scan_targets where url = 'https://www.jumia.com.eg/rls-phone-test/';

-- tg_posts: readable by signed-in users, not writable by them, invisible to anon
insert into public.tg_posts (channel, post_id, text) values ('rlstest', 1, 'hello');
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a1111111-1111-4111-8111-111111111111","role":"authenticated"}', true);
do $$ begin
  if (select count(*) from public.tg_posts where channel = 'rlstest') <> 1 then raise exception 'tg_posts not readable'; end if;
  begin insert into public.tg_posts (channel, post_id) values ('rlstest', 2); raise exception 'tg_posts writable';
  exception when insufficient_privilege then null; end;
end $$;
reset role;
set local role anon;
do $$ begin
  begin perform 1 from public.tg_posts; raise exception 'tg_posts visible to anon';
  exception when insufficient_privilege then null; end;
end $$;
reset role;
delete from public.tg_posts where channel = 'rlstest';

select 'ALL RLS TESTS PASSED';
