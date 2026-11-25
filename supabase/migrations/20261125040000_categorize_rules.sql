-- 1) Instant categories without AI: keyword rules on the title (the AI audit refines them later).
--    Every active deal gets a side-menu category right away, so no category page is empty while the AI works.
create or replace function public.categorize_by_keywords(p_limit int default 50000)
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  with rules(cat, pat, prio) as (values
    ('إلكترونيات/موبايلات وتابلت', '(iphone|galaxy|smartphone|mobile phone|redmi|xiaomi [0-9]|oppo|realme|tablet|ipad|موبايل|تابلت|هاتف)', 10),
    ('إلكترونيات/ساعات ذكية', '(smart ?watch|smartwatch|fitness tracker|ساعة ذكية|band [0-9])', 9),
    ('إلكترونيات/سماعات وصوتيات', '(headphone|earbuds|earphone|headset|speaker|soundbar|airpods|سماع)', 9),
    ('إلكترونيات/لابتوب وكمبيوتر', '(laptop|notebook pc|macbook|keyboard|mouse|monitor|ssd|hard drive|router|printer|لابتوب|كمبيوتر|ماوس|كيبورد)', 9),
    ('إلكترونيات/تلفزيونات وشاشات', '(smart tv|\mtv\M|television|شاشة|تلفزيون)', 9),
    ('إلكترونيات/ألعاب فيديو', '(playstation|ps5|ps4|xbox|nintendo|gaming|controller|بلايستيشن)', 9),
    ('إلكترونيات/كاميرات', '(camera|webcam|dash cam|كاميرا)', 8),
    ('إلكترونيات/اكسسوارات وشواحن', '(charger|cable|power ?bank|usb|adapter|screen protector|phone case|cover for|شاحن|كابل|باور بانك|جراب)', 7),
    ('أجهزة منزلية/كبيرة', '(refrigerator|fridge|washing machine|dishwasher|air condition|freezer|cooker|oven|ثلاجة|غسالة|تكييف|بوتاجاز|ديب فريزر)', 9),
    ('أجهزة منزلية/صغيرة', '(air fryer|blender|mixer|kettle|toaster|coffee maker|microwave|iron|vacuum|hair dryer|straightener|shaver|trimmer|قلاية|خلاط|كاتل|مكواة|مكنسة|سشوار)', 8),
    ('جمال وعناية/عطور', '(perfume|parfum|eau de|fragrance|body mist|عطر|برفان)', 9),
    ('جمال وعناية/مكياج', '(lipstick|mascara|foundation|makeup|eyeliner|concealer|blush|مكياج|روج)', 9),
    ('جمال وعناية/عناية شخصية', '(shampoo|conditioner|toothpaste|tooth|deodorant|cream|lotion|serum|sunscreen|soap|shower gel|hair oil|razor|شامبو|معجون|كريم|صابون|مزيل)', 7),
    ('صحة ورياضة/صحة ومكملات', '(vitamin|supplement|protein|omega|collagen|فيتامين|مكمل)', 8),
    ('صحة ورياضة/رياضة', '(dumbbell|yoga|treadmill|bicycle|football|gym|fitness|sport bottle|دمبل|عجلة|رياضة)', 6),
    ('أطفال ولعب/مستلزمات أطفال', '(diaper|baby|stroller|pampers|feeding bottle|حفاض|بيبي|رضاعة)', 8),
    ('أطفال ولعب/لعب', '(\mtoy|lego|puzzle|doll|rc car|لعبة|لعب)', 7),
    ('ملابس/أحذية', '(shoe|sneaker|sandal|slipper|boot|trainers|heels|loafer|حذاء|جزمة|كوتشي|صندل|شبشب)', 8),
    ('ملابس/شنط واكسسوارات', '(\mbag|backpack|wallet|handbag|sunglasses|belt|jewel|necklace|bracelet|earring|شنطة|محفظة|نظارة|حزام)', 7),
    ('ملابس/أطفال', '((boys|girls|kids|baby|toddler|junior).*(shirt|dress|pants|set|jacket|hoodie|short|t-shirt|pajama))|ولادي|بناتي', 7),
    ('ملابس/حريمي', '(women|woman|ladies|girl''s|dress|skirt|blouse|abaya|hijab|leggings|حريمي|فستان|بلوزة|جيبة)', 6),
    ('ملابس/رجالي', '(\mmen\M|men''s|\mman\M|polo|shirt|t-shirt|tshirt|jeans|trouser|pants|hoodie|sweatshirt|jacket|short|pullover|سويت|تيشرت|قميص|بنطلون|رجالي)', 5),
    ('البيت والمطبخ/مطبخ', '(cookware|pan|pot|knife|cutlery|plate|mug|cup|glass|kitchen|food storage|حلة|طاسة|سكينة|طبق|مج|مطبخ)', 6),
    ('البيت والمطبخ/ديكور ومفروشات', '(bed sheet|pillow|blanket|towel|curtain|carpet|rug|duvet|lamp|decor|مفرش|مخدة|بطانية|فوطة|ستارة|سجادة)', 6),
    ('البيت والمطبخ/أدوات وعدد', '(drill|screwdriver|tool ?kit|wrench|hammer|بنسة|شنيور|عدة)', 7),
    ('سوبرماركت/منظفات', '(detergent|cleaner|dishwashing|tissue|bleach|fabric softener|منظف|مناديل|كلور)', 7),
    ('سوبرماركت/أكل ومشروبات', '(coffee|tea|chocolate|biscuit|snack|juice|oil|rice|pasta|honey|nuts|قهوة|شاي|شوكولاتة|بسكويت|عسل|زيت)', 5),
    ('عربيات/اكسسوارات عربيات', '(car |for car|tyre|tire|car charger|عربية|سيارة)', 6),
    ('كتب ومكتبة/أدوات مكتبية', '(notebook|pen |pencil|stationery|كشكول|قلم)', 5),
    ('حيوانات أليفة/مستلزمات', '(\mcat food|dog|pet |قطط|كلاب)', 7)
  ), pick as (
    select distinct on (d.id) d.id, r.cat
    from (select id, title from public.deals where active and category is null limit p_limit) d
    join rules r on lower(d.title) ~* r.pat
    order by d.id, r.prio desc
  )
  update public.deals d set category = p.cat, updated_at = now() from pick p where d.id = p.id;
  get diagnostics n = row_count;
  return n;
end $$;
revoke all on function public.categorize_by_keywords(int) from public, anon, authenticated;
grant execute on function public.categorize_by_keywords(int) to service_role;

select public.categorize_by_keywords(200000);

-- 2) SHEIN pricing (owner, 2026-11-25 02:32): Saudi original price × 14 × 1.20; shipping 130 EGP once per order.
update public.app_config set value = '{"sar_to_egp":"14","multiplier":"1.20","delivery_fee_egp":"130"}'::jsonb
where key = 'shein_pricing';
