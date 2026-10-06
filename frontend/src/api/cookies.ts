// Reads a JS-visible cookie by exact name. Its own module so tests can vi.mock it: jsdom will not
// reliably hold a __Host- cookie on http://localhost, and a spy inside the calling module would not
// intercept under ESM. The session cookie is HttpOnly and can never be read here -- by design.

export function readCookie(name: string): string | null {
  for (const part of document.cookie.split(";")) {
    const eq = part.indexOf("=");
    if (eq < 0) continue;
    if (part.slice(0, eq).trim() === name) {
      const value = part.slice(eq + 1).trim();
      if (value === "") return null;
      try {
        return decodeURIComponent(value);
      } catch {
        return value; // malformed escape (e.g. a planted cookie): the raw value, never a throw
      }
    }
  }
  return null;
}
