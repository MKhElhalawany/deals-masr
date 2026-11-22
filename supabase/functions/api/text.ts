// Arabic/English query handling (pure functions, unit-tested).

const TASHKEEL = /[ً-ٰٟـ]/g;
const AR_DIGITS = "٠١٢٣٤٥٦٧٨٩";

/** Normalises Arabic spelling variants and digits; lower-cases Latin. */
export function normalize(q: string): string {
  return q.replace(TASHKEEL, "")
    .replace(/[إأآٱ]/g, "ا").replace(/ى/g, "ي").replace(/ة(?=\s|$)/g, "ه").replace(/ؤ/g, "و").replace(/ئ/g, "ي")
    .replace(/[٠-٩]/g, (d) => String(AR_DIGITS.indexOf(d)))
    .toLowerCase().replace(/[^\p{L}\p{N}\s.\-+]/gu, " ").replace(/\s+/g, " ").trim();
}

export const hasArabic = (s: string) => /[؀-ۿ]/.test(s);

// Common Egyptian shopping words → English product terms (saves AI calls; the AI handles the rest).
const DICT: [RegExp, string][] = [
  [/قلايه هواييه|قلايه|ايرفراير|اير فراير/, "air fryer"], [/سماعه بلوتوث|ايربودز|سماعات|سماعه/, "headphones"],
  [/موبايل|تليفون|هاتف|جوال/, "mobile phone"], [/ايفون/, "iphone"], [/سامسونج/, "samsung"], [/شاومي/, "xiaomi"],
  [/شاحن/, "charger"], [/باور بانك|باوربانك/, "power bank"], [/لابتوب|لاب توب/, "laptop"], [/تابلت|ايباد/, "tablet"],
  [/ساعه ذكيه|سمارت واتش/, "smart watch"], [/ساعه/, "watch"], [/تلفزيون|تليفزيون|شاشه/, "tv"],
  [/غساله اطباق/, "dishwasher"], [/غساله/, "washing machine"], [/تلاجه|ثلاجه/, "refrigerator"], [/ديب فريزر|فريزر/, "freezer"],
  [/تكييف|مكيف/, "air conditioner"], [/مروحه/, "fan"], [/سخان/, "water heater"], [/بوتاجاز|موقد/, "cooker"],
  [/ميكروويف/, "microwave"], [/مكنسه/, "vacuum cleaner"], [/خلاط/, "blender"], [/كبه|كبيه/, "kettle"],
  [/ماكينه قهوه|مكنه قهوه/, "coffee maker"], [/مكواه/, "iron"], [/مجفف شعر|سشوار/, "hair dryer"], [/ماكينه حلاقه/, "shaver"],
  [/جزمه رياضي|كوتشي/, "sneakers"], [/جزمه كوره|حذاء كره قدم|جزمه/, "shoes"], [/صندل/, "sandals"], [/شبشب/, "slippers"],
  [/برفان|برفيوم|عطر/, "perfume"], [/مكياج/, "makeup"], [/كريم/, "cream"], [/شامبو/, "shampoo"],
  [/فستان/, "dress"], [/قميص/, "shirt"], [/تيشيرت|تي شيرت/, "t-shirt"], [/بنطلون|بنطلونات/, "pants"], [/جينز/, "jeans"],
  [/جاكت|جاكيت/, "jacket"], [/هودي/, "hoodie"], [/بيجامه/, "pajama"], [/شنطه/, "bag"], [/نضاره/, "sunglasses"],
  [/كرسي جيمنج|كرسي العاب/, "gaming chair"], [/كرسي مكتب/, "office chair"], [/كرسي/, "chair"], [/مكتب/, "desk"],
  [/مرتبه/, "mattress"], [/كاميرا/, "camera"], [/طابعه/, "printer"], [/ماوس/, "mouse"], [/كيبورد|لوحه مفاتيح/, "keyboard"],
  [/بلايستيشن|بلاي ستيشن/, "playstation"], [/كارت شاشه/, "graphics card"], [/هارد/, "hard drive"], [/فلاشه/, "usb flash drive"],
  [/راوتر/, "router"], [/عربيه اطفال/, "baby stroller"], [/حفاضات|بامبرز/, "diapers"], [/لعب|لعبه/, "toy"],
];

/** Dictionary translation; returns null when part of the query is not covered (then the AI is asked). */
export function dictTranslate(qNorm: string): string | null {
  let rest = qNorm;
  const parts: string[] = [];
  for (const [re, en] of DICT) {
    const m = rest.match(re);
    if (m) { parts.push(en); rest = rest.replace(re, " "); }
  }
  const FILLER = new Set(["عايز", "عاوز", "محتاج", "ابحث", "دور", "على", "علي", "عن", "ل", "في", "من", "رخيص", "رخيصه", "كويس",
    "كويسه", "حلو", "حلوه", "افضل", "احسن", "ارخص", "و", "او", "اللي", "بتاع", "بتاعه"]);
  const left = rest.split(/\s+/).filter((w) => w && !FILLER.has(w));
  const latin = left.filter((w) => /^[a-z0-9.\-+]+$/.test(w));
  const leftoverArabic = left.filter((w) => hasArabic(w));
  if (!parts.length || leftoverArabic.length) return null;
  return [...parts, ...latin].join(" ");
}

const STOP = new Set(["the", "and", "for", "with", "of", "to", "in", "on", "a", "an", "by", "pack", "new", "set", "pcs", "piece",
  "men", "women", "black", "white", "size", "color"]);

/** Search words of one query variant (max 5, longest first, no stop words). */
export function words(q: string): string[] {
  return [...new Set(normalize(q).split(" ").filter((w) => w.length >= 2 && !STOP.has(w)))].slice(0, 5);
}

/** Distinctive tokens of a product title for cross-store matching (model numbers first). */
export function titleTokens(title: string): string[] {
  const ws = normalize(title).split(" ").filter((w) => w.length >= 3 && !STOP.has(w));
  const model = ws.filter((w) => /\d/.test(w) && /[a-z]/.test(w));
  const rest = ws.filter((w) => !model.includes(w));
  return [...new Set([...model, ...rest])].slice(0, 8);
}

export function similarity(a: string, b: string): number {
  const A = new Set(titleTokens(a)), B = new Set(titleTokens(b));
  if (!A.size || !B.size) return 0;
  let n = 0; for (const x of A) if (B.has(x)) n++;
  return n / Math.min(A.size, B.size);
}

/** "تحت 15000" / "under 15000" / "اقل من 1000" → max price */
export function maxPriceOf(q: string): number | null {
  const m = normalize(q).match(/(?:تحت|اقل من|under|below|max|ب)\s*([0-9][0-9,.]*)\s*(?:k|الف|ألف)?/);
  if (!m) return null;
  let v = parseFloat(m[1].replace(/,/g, ""));
  if (/الف|ألف|k\b/.test(m[0])) v *= 1000;
  return v > 0 ? v : null;
}
