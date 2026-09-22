import eslint from "@eslint/js";
import tseslint from "typescript-eslint";
import pluginVue from "eslint-plugin-vue";
import globals from "globals";

export default tseslint.config(
  { ignores: ["dist", "node_modules"] },
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs["flat/recommended"],
  {
    // .vue files use vue-eslint-parser at the top level; the <script lang="ts">
    // block is delegated to the typescript-eslint parser.
    files: ["**/*.vue"],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
      },
    },
  },
  {
    // Formatting is Prettier's job, not ESLint's — turn off eslint-plugin-vue's
    // stylistic/layout rules so the two tools don't disagree.
    rules: {
      "vue/singleline-html-element-content-newline": "off",
      "vue/max-attributes-per-line": "off",
      "vue/html-self-closing": "off",
    },
  },
  {
    // The build-tooling scripts are Node programs, not browser code. They must be linted
    // — security-scan.mjs IS the dependency-scan gate, and exempting a gate's own
    // implementation from linting is the wrong direction — so this teaches ESLint what
    // globals they actually have.
    //
    // Both halves are needed. eslint-plugin-vue's flat/recommended carries an UNSCOPED
    // `vue/base/setup` block that applies all 763 browser globals to every file in the
    // repo; that is why `console` resolves here today while `process` does not. Adding
    // the Node set alone would leave `window`, `document` and `localStorage` defined
    // inside a Node script.
    //
    // ORDER IS LOAD-BEARING: the two sets overlap on 60 names (`console`, `fetch`, `URL`,
    // `crypto`, `setTimeout`, …), so `globals.node` MUST be spread last to win on the
    // overlap. Reversed, browser-"off" would strip `console` and simply trade one set of
    // no-undef errors for another. Only the 703 browser-exclusive names end up "off".
    //
    // The glob includes .ts for globals coverage, but note that typescript-eslint
    // disables `no-undef` for TypeScript (tsc reports unknown identifiers instead), so
    // do not read that extension as gate coverage.
    files: ["scripts/**/*.{js,mjs,cjs,ts}"],
    languageOptions: {
      globals: {
        ...Object.fromEntries(
          Object.keys(globals.browser).map((key) => [key, "off"]),
        ),
        ...globals.node,
        // `globals` v14 classifies these as browser-only, but they are real on globalThis in
        // modern Node. Without them a tooling script using global `WebSocket` or `navigator`
        // would get a false `no-undef` from the "off" sweep above.
        navigator: "readonly",
        Navigator: "readonly",
        WebSocket: "readonly",
        Performance: "readonly",
        CloseEvent: "readonly",
      },
    },
  },
);
