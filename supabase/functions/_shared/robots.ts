// robots.txt rules shared by the scan and api functions.
// ───────── robots.txt (RFC 9309: groups, * and $ wildcards, longest match wins, Allow wins ties)
export class Robots {
  rules: [string, string][] = [];
  constructor(text: string | null) {
    if (!text) return;
    let star = false, prevUa = false;
    for (const raw of text.split(/\r?\n/)) {
      const line = raw.split("#")[0].trim();
      const i = line.indexOf(":");
      if (i < 0) continue;
      const k = line.slice(0, i).trim().toLowerCase(), v = line.slice(i + 1).trim();
      if (k === "user-agent") { if (!prevUa) star = false; star = star || v === "*"; prevUa = true; continue; }
      prevUa = false;
      if (star && (k === "allow" || k === "disallow") && v) this.rules.push([k, v]);
    }
  }
  private static rx(rule: string) {
    const end = rule.endsWith("$");
    const body = rule.replace(/\$$/, "").split("*").map((p) => p.replace(/[.+?^${}()|[\]\\]/g, "\\$&")).join(".*");
    return new RegExp("^" + body + (end ? "$" : ""));
  }
  ok(url: string): boolean {
    const u = new URL(url);
    const path = (u.pathname || "/") + (u.search || "");
    let best: [string, string] | null = null;
    for (const r of this.rules) {
      if (Robots.rx(r[1]).test(path) && (!best || r[1].length > best[1].length || (r[1].length === best[1].length && r[0] === "allow"))) best = r;
    }
    return !best || best[0] === "allow";
  }
}

