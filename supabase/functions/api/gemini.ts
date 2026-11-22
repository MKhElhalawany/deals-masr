// Google Gemini (free tier). The model is chosen from the models this key can use, so a retired model name
// never breaks the app. Returns null on any failure (quota, network) so callers can fall back.
const KEY = Deno.env.get("GEMINI_API_KEY") ?? "";
const BASE = "https://generativelanguage.googleapis.com/v1beta";
let chosen: { name: string; at: number } | null = null;
export const geminiConfigured = () => KEY.length > 10;

async function model(): Promise<string | null> {
  if (chosen && Date.now() - chosen.at < 6 * 3600_000) return chosen.name;
  const r = await fetch(`${BASE}/models?pageSize=200&key=${KEY}`, { signal: AbortSignal.timeout(10_000) });
  if (!r.ok) return null;
  const all: any[] = (await r.json()).models ?? [];
  const usable = all.filter((m) => (m.supportedGenerationMethods ?? []).includes("generateContent"))
    .map((m) => String(m.name))
    .filter((n) => /gemini/.test(n) && !/(tts|image|vision|embedding|live|audio|preview|exp|thinking|learnlm|nano|robotics|computer)/.test(n));
  const ver = (n: string) => parseFloat(n.match(/gemini-(\d+(?:\.\d+)?)/)?.[1] ?? "0");
  const rank = (n: string) => (/flash-lite/.test(n) ? 3 : /flash/.test(n) ? 2 : 1) * 100 + ver(n) * 10 - (/latest/.test(n) ? 0 : 0.1);
  usable.sort((a, b) => rank(b) - rank(a));
  if (!usable.length) return null;
  chosen = { name: usable[0], at: Date.now() };
  return chosen.name;
}

export type Turn = { role: "user" | "model"; text: string };

export async function gemini(system: string, turns: Turn[], opts: { json?: boolean; maxTokens?: number; temperature?: number } = {}):
  Promise<{ text: string; model: string } | null> {
  if (!geminiConfigured()) return null;
  try {
    const m = await model();
    if (!m) return null;
    const body = {
      systemInstruction: { parts: [{ text: system }] },
      contents: turns.map((t) => ({ role: t.role, parts: [{ text: t.text }] })),
      generationConfig: { temperature: opts.temperature ?? 0.4, maxOutputTokens: opts.maxTokens ?? 700,
        ...(opts.json ? { responseMimeType: "application/json" } : {}) },
    };
    const r = await fetch(`${BASE}/${m}:generateContent?key=${KEY}`, { method: "POST", signal: AbortSignal.timeout(25_000),
      headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    if (!r.ok) { console.warn("gemini", r.status, (await r.text()).slice(0, 200)); if (r.status === 404) chosen = null; return null; }
    const j = await r.json();
    const text = (j.candidates?.[0]?.content?.parts ?? []).map((p: any) => p.text ?? "").join("").trim();
    return text ? { text, model: m.replace("models/", "") } : null;
  } catch (e) { console.warn("gemini error", String(e)); return null; }
}
