import eslint from "@eslint/js";
import tseslint from "typescript-eslint";
import pluginVue from "eslint-plugin-vue";

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
);
