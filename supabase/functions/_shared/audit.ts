// AI audit of deals: category (sidebar menu) + fake "before price" detection. Free Gemini tier, 40 deals per call.
import { gemini } from "./gemini.ts";

export const CATEGORIES = [
  "ملابس/رجالي", "ملابس/حريمي", "ملابس/أطفال", "ملابس/أحذية", "ملابس/شنط واكسسوارات",
  "إلكترونيات/موبايلات وتابلت", "إلكترونيات/لابتوب وكمبيوتر", "إلكترونيات/سماعات وصوتيات", "إلكترونيات/تلفزيونات وشاشات",
  "إلكترونيات/ألعاب فيديو", "إلكترونيات/كاميرات", "إلكترونيات/اكسسوارات وشواحن", "إلكترونيات/ساعات ذكية",
  "أجهزة منزلية/كبيرة", "أجهزة منزلية/صغيرة", "البيت والمطبخ/مطبخ", "البيت والمطبخ/ديكور ومفروشات", "البيت والمطبخ/أدوات وعدد",
  "جمال وعناية/مكياج", "جمال وعناية/عطور", "جمال وعناية/عناية شخصية", "صحة ورياضة/رياضة", "صحة ورياضة/صحة ومكملات",
  "أطفال ولعب/لعب", "أطفال ولعب/مستلزمات أطفال", "سوبرماركت/أكل ومشروبات", "سوبرماركت/منظفات", "عربيات/اكسسوارات عربيات",
  "كتب ومكتبة/كتب", "كتب ومكتبة/أدوات مكتبية", "حيوانات أليفة/مستلزمات", "أخرى/أخرى",
];

export type AuditIn = { id: string; title: string; store: string; current_price: number; original_price: number | null };
export type AuditOut = { id: string; status: "ok" | "fake"; note: string; category: string; typical_low: number | null; typical_high: number | null };

const SYSTEM = `You audit discounted product listings from Egyptian online stores (prices in EGP, 2026).
For EACH item return an object: {"id", "category", "typical_low", "typical_high", "fake", "reason"}.
- category: exactly one value from this list: ${JSON.stringify(CATEGORIES)}
- typical_low/typical_high: your best estimate of the normal (non-sale) retail price range in Egypt for this exact kind of product, in EGP. Use null if you cannot judge.
- fake: true ONLY when the "before" price is clearly inflated for this kind of product (for example a normal toothpaste with a 1000 EGP before-price), or the listing is nonsense. Big real discounts on real brands are NOT fake. When unsure: false.
- reason: very short Arabic reason when fake, else "".
Reply with a JSON array only.`;

/** Deterministic guard used with (or without) the AI: a "before" price many times the normal price. */
export function inflated(i: AuditIn, typicalHigh: number | null): boolean {
  if (!i.original_price) return false;
  if (i.original_price >= i.current_price * 15) return true;                       // 93%+ off: almost always a fake before-price
  return !!typicalHigh && i.original_price > typicalHigh * 3 && i.current_price <= typicalHigh * 1.5;
}

export async function audit(items: AuditIn[]): Promise<AuditOut[] | null> {
  if (!items.length) return [];
  const g = await gemini(SYSTEM, [{ role: "user", text: JSON.stringify(items.map((i) => ({
    id: i.id, title: i.title.slice(0, 140), store: i.store, price: i.current_price, before: i.original_price }))) }],
    { json: true, maxTokens: 8000, temperature: 0 });
  if (!g) return null;
  let rows: any = [];
  try { rows = JSON.parse(g.text); } catch { throw new Error(`audit: bad JSON from ${g.model}: ${g.text.slice(0, 200)} … ${g.text.slice(-120)}`); }
  if (!Array.isArray(rows)) rows = Object.values(rows ?? {}).find(Array.isArray) ?? [];
  const byId = new Map(items.map((i) => [i.id, i]));
  return (rows as any[]).filter((r: any) => byId.has(String(r?.id))).map((r: any) => {
    const i = byId.get(String(r.id))!;
    const lo = Number(r.typical_low) > 0 ? Number(r.typical_low) : null, hi = Number(r.typical_high) > 0 ? Number(r.typical_high) : null;
    const fake = r.fake === true || inflated(i, hi);
    return { id: i.id, status: fake ? "fake" : "ok", note: fake ? String(r.reason || "السعر قبل الخصم مبالغ فيه").slice(0, 200) : "",
      category: CATEGORIES.includes(r.category) ? r.category : "أخرى/أخرى", typical_low: lo, typical_high: hi };
  });
}
