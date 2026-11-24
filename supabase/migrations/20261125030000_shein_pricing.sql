-- SHEIN pricing (owner, 2026-11-25): SHEIN Saudi original (crossed-out) price × 14.5 × 1.20, delivery included (0).
update public.app_config set value = '{"sar_to_egp":"14.5","multiplier":"1.20","delivery_fee_egp":"0"}'::jsonb
where key = 'shein_pricing';
