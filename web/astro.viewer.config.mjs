import { defineConfig } from "astro/config";
export default defineConfig({
  srcDir: "./viewer",
  publicDir: "./viewer-public",
  outDir: "./dist-viewer",
  base: "/app",
  output: "static",
  trailingSlash: "always",
  build: { format: "directory" },
});
