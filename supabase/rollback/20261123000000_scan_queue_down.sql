-- ROLLBACK for 20261123000000_scan_queue.sql — removes the queue objects only. Deals, price history, users untouched.
-- Run AFTER setting app_config scanner_mode = 'rotation' (the scanner then ignores the queue).
drop function if exists public.add_targets(text, jsonb);
drop function if exists public.finish_target(bigint, text, int, int, int, text, int);
drop function if exists public.claim_targets(text, int);
drop table if exists public.scan_log;
drop table if exists public.robots_cache;
drop table if exists public.scan_targets;
-- the 'townteam' store row is kept (Town Team deals reference it); harmless if unused
