import { defineConfig } from "astro/config";

// Static marketing site: no server adapter or SSR. The homepage forms remain demo-only until
// their production workflows are specified.
export default defineConfig({
  output: "static",
  site: process.env.PUBLIC_SITE_URL ?? "https://tenderbell.co.zw",
});
