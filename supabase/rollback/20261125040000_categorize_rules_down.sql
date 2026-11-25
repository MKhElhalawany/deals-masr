drop function if exists public.categorize_by_keywords(int);
update public.app_config set value = '{"sar_to_egp":"14.5","multiplier":"1.20","delivery_fee_egp":"0"}'::jsonb where key = 'shein_pricing';
