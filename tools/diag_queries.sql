-- read-only diagnostics printed by CI (edge job). One query per line.
select store, status, count(*), max(at) from public.phone_reports group by 1,2 order by 1,2;
select store, ok, message, last_run from public.scraper_status order by store;
select store_id, active, count(*) from public.deals group by 1,2 order by 1,2;
select id, title, current_price, original_price, deal_pct from public.deals where active and title ilike '%tooth%' order by deal_pct desc limit 8;
select store_id, count(*) filter (where deal_pct >= 70) as mega, count(*) filter (where deal_pct >= 70 and original_price >= current_price * 8) as x8 from public.deals where active group by 1;
