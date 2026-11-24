// Google AI (free tier). Several free models are used in turn: when one is busy or out of its daily free quota
// (HTTP 429/5xx) the next one answers, so the app keeps working. Models are discovered from the key's model list,
// so a retired model name never breaks the app. Returns null on any failure so callers can fall back.
const KEY = Deno.env.get("GEMINI_API_KEY") ?? "";
const BASE = "https://generativelanguage.googleapis.com/v1beta";
let chain: { names: string[]; at: number } | null = null;
const resting = new Map<string, number>();   // model → time until which it is skipped (quota hit)
export const geminiConfigured = () => KEY.length > 10;

async function models(): Promise<string[]> {
  if (chain && Date.now() - chain.at < 6 * 3600_000) return chain.names;
  const r = await fetch(`${BASE}/models?pageSize=300&key=${KEY}`, { signal: AbortSignal.timeout(10_000) });
  if (!r.ok) return chain?.names ?? [];
  const all: any[] = (await r.json()).models ?? [];
  const usable = all.filter((m) => (m.supportedGenerationMethods ?? []).includes("generateContent")).map((m) => String(m.name))
    .filter((n) => /gemini|gemma/.test(n) && !/(tts|image|vision|embedding|live|audio|preview|exp|thinking|learnlm|nano|robotics|computer|-e\d+b|-1b|-4b)/.test(n));
  const ver = (n: string) => parseFloat(n.match(/(?:gemini|gemma)-(\d+(?:\.\d+)?)/)?.[1] ?? "0");
  const size = (n: string) => parseFloat(n.match(/-(\d+)b/)?.[1] ?? "0");
  // fast free Gemini first, then the bigger Gemini, then open Gemma models (large free quota)
  const rank = (n: string) => (/gemma/.test(n) ? 0 : /flash-lite/.test(n) ? 300 : /flash/.test(n) ? 200 : 100) + ver(n) * 10 + size(n) / 10;
  usable.sort((a, b) => rank(b) - rank(a));
  const pick: string[] = [];
  for (const fam of [/flash-lite/, /gemini.*flash(?!-lite)/, /gemma/]) {
    for (const n of usable) if (fam.test(n) && !pick.includes(n)) { pick.push(n); break; }
  }
  chain = { names: pick.length ? pick : usable.slice(0, 3), at: Date.now() };
  return chain.names;
}

export type Turn = { role: "user" | "model"; text: string };

export async function gemini(system: string, turns: Turn[], opts: { json?: boolean; maxTokens?: number; temperature?: number } = {}):
  Promise<{ text: string; model: string } | null> {
  if (!geminiConfigured()) return null;
  let list: string[] = [];
  try { list = await models(); } catch (e) { console.warn("models", String(e)); }
  for (const m of list) {
    if ((resting.get(m) ?? 0) > Date.now()) continue;
    const gemma = /gemma/.test(m);
    // Gemma has no system field / JSON mode: the instructions go first in the conversation
    const contents = turns.map((t, i) => ({ role: t.role, parts: [{ text: gemma && i === 0 ? `${system}\n\n${t.text}` +
      (opts.json ? "\n\nReply with JSON only, no other text." : "") : t.text }] }));
    const body: Record<string, unknown> = {
      contents,
      generationConfig: { temperature: opts.temperature ?? 0.4, maxOutputTokens: opts.maxTokens ?? 700,
        ...(opts.json && !gemma ? { responseMimeType: "application/json" } : {}) },
    };
    if (!gemma) body.systemInstruction = { parts: [{ text: system }] };
    try {
      const r = await fetch(`${BASE}/${m}:generateContent?key=${KEY}`, { method: "POST", signal: AbortSignal.timeout(25_000),
        headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
      if (!r.ok) {
        console.warn("ai", m, r.status, (await r.text()).slice(0, 160));
        if (r.status === 404) chain = null;
        resting.set(m, Date.now() + (r.status === 429 ? 10 * 60_000 : 60_000));
        continue;
      }
      const j = await r.json();
      let text = (j.candidates?.[0]?.content?.parts ?? []).map((p: any) => p.text ?? "").join("").trim();
      if (opts.json) text = text.replace(/^```(?:json)?\s*/i, "").replace(/```\s*$/, "").trim();
      if (text) return { text, model: m.replace("models/", "") };
    } catch (e) { console.warn("ai error", m, String(e)); resting.set(m, Date.now() + 60_000); }
  }
  return null;
}
