import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    // Proxy API calls to the Spring Boot backend during dev.
    // Backend runs on 8090 locally (8080 is taken by another stack).
    proxy: {
      "/api": "http://localhost:8090",
    },
  },
});
