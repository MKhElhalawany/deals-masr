-- read-only diagnostics printed by CI (edge job). One query per line.
select id, title, current_price, original_price, discount_pct, active, deal_since, last_checked, updated_at from public.deals where title ilike '%Vero Moda Women Dresses%' limit 5;
select store_id, active, count(*) from public.deals group by 1,2 order by 1,2;
select id, current_price, original_price, discount_pct, active from public.deals where store_id='noon' and active order by updated_at desc limit 5;
select id, title, current_price, original_price, discount_pct from public.deals where store_id='amazon' and active order by discount_pct desc limit 5;
select status, count(*) from public.scan_log where at > now() - interval '1 hour' group by 1;
