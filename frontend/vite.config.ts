/// <reference types="vitest/config" />
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";
import { assertOperatorEnv } from "./src/content/operatorFacts";

// From this file, never process.cwd(): render-terms.mjs runs from anywhere, and the gate must read
// exactly the .env files Vite bakes from.
const frontendDir = dirname(fileURLToPath(import.meta.url));

export default defineConfig(({ mode }) => {
  // Fail closed on a malformed operator identifier (operating-entity-disclosure D4). Runs for build,
  // dev, Vitest and render-terms alike; loadEnv reads .env* files plus process.env.
  assertOperatorEnv(loadEnv(mode, frontendDir, "VITE_OPERATOR_"));

  return {
    envDir: frontendDir,
    plugins: [vue()],
    server: {
      // Proxy API calls to the Spring Boot backend during dev.
      // Backend runs on 8090 locally (8080 is taken by another stack).
      proxy: {
        "/api": "http://localhost:8090",
      },
    },
    test: {
      // Component tests mount into a DOM; unit tests mock fetch. jsdom serves both.
      environment: "jsdom",
      globals: true,
    },
  };
});
