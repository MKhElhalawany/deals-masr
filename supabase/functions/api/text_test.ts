import { dictTranslate, maxPriceOf, normalize, similarity, titleTokens, words } from "./text.ts";
function eq(a: unknown, b: unknown) { const x = JSON.stringify(a), y = JSON.stringify(b); if (x !== y) throw new Error(`${x} !== ${y}`); }
Deno.test("normalize arabic", () => { eq(normalize("قلّاية هوائيّة"), "قلايه هواييه"); eq(normalize("أيفون ١٥"), "ايفون 15"); });
Deno.test("dictionary", () => {
  eq(dictTranslate(normalize("قلاية")), "air fryer");
  eq(dictTranslate(normalize("عايز سماعة بلوتوث")), "headphones");
  eq(dictTranslate(normalize("جزمة كورة")), "shoes");
  eq(dictTranslate(normalize("ايفون 15 pro")), "iphone 15 pro");
  eq(dictTranslate(normalize("كنبة مودرن")), null);
});
Deno.test("words + tokens", () => {
  eq(words("Air Fryer for the kitchen"), ["air", "fryer", "kitchen"]);
  eq(titleTokens("Samsung Galaxy A55 5G 256GB Black")[0], "a55");
  eq(similarity("Samsung Galaxy A55 5G 256GB", "SAMSUNG Galaxy A55 256GB Awesome Navy") >= 0.6, true);
});
Deno.test("max price", () => { eq(maxPriceOf("موبايل تحت 15000"), 15000); eq(maxPriceOf("laptop under 20k"), 20000); eq(maxPriceOf("قلاية"), null); });
