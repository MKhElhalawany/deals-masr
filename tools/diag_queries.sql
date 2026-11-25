-- read-only diagnostics printed by CI (edge job). One query per line.
select store, status, count(*), max(at) from public.phone_reports group by 1,2 order by 1,2;
select store, ok, message, last_run from public.scraper_status order by store;
select store_id, active, count(*) from public.deals group by 1,2 order by 1,2;
select id, title, current_price, original_price, deal_pct from public.deals where active and title ilike '%tooth%' order by deal_pct desc limit 8;
select store_id, count(*) filter (where deal_pct >= 70) as mega, count(*) filter (where deal_pct >= 70 and original_price >= current_price * 8) as x8 from public.deals where active group by 1;
select status, count(*), sum(items) as items, sum(saved) as saved, max(note) as note from public.phone_reports where at > now() - interval '3 days' group by 1;
select count(*) as posts, count(price) as with_price, min(post_id), max(post_id), max(posted_at) from public.tg_posts;
select audit_status, count(*) from public.deals where active or audit_status = 'fake' group by 1;
select category, count(*) from public.deals where active group by 1 order by 2 desc limit 40;
select left(title, 60) as t, current_price as p, original_price as o, typical_low as lo, typical_high as hi, audit_note from public.deals where audit_status = 'fake' order by audited_at desc limit 25;
select left(title, 50) as t, current_price as p, original_price as o, typical_high as hi, category from public.deals where audit_status = 'ok' order by audited_at desc limit 12;
select kind, left(message, 160) as msg, url, count(*), max(at) from public.client_logs where at > now() - interval '2 days' group by 1,2,3 order by 5 desc limit 40;
