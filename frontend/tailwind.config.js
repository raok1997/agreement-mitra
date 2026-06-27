/** @type {import('tailwindcss').Config} */

// Colors are sourced from CSS custom properties (see src/style.css) so the SPA,
// the rendered PDF templates, and the logo all draw from one palette. The vars
// hold space-separated RGB channels, which lets Tailwind apply opacity via
// <alpha-value> (e.g. bg-brand-600/40).
const ch = (token) => `rgb(var(--am-${token}) / <alpha-value>)`;
const STEPS = [50, 100, 200, 300, 400, 500, 600, 700, 800, 900];
const scale = (name) =>
  Object.fromEntries(STEPS.map((step) => [step, ch(`${name}-${step}`)]));

const indic = ["Inter", "'Noto Sans Devanagari'", "system-ui", "sans-serif"];

export default {
  content: ["./index.html", "./src/**/*.{vue,ts}"],
  theme: {
    extend: {
      colors: {
        // brand — deep indigo "trust": legal seriousness without going cold
        brand: { ...scale("brand"), DEFAULT: ch("brand-600") },
        // accent — saffron "mitra": warmth, CTAs
        accent: { ...scale("accent"), DEFAULT: ch("accent-500") },
        // success — verified / signed / audit-trail (the transparency wedge)
        success: { ...scale("success"), DEFAULT: ch("success-600") },
        // ink — warm-gray neutrals for surfaces + text
        ink: { ...scale("ink"), DEFAULT: ch("ink-800") },
      },
      // Latin renders in Inter; Devanagari glyphs fall through to Noto
      // automatically (the browser picks the font that has the glyph), so a
      // single stack handles bilingual agreements.
      fontFamily: {
        sans: indic,
        display: indic,
      },
    },
  },
  plugins: [],
};
