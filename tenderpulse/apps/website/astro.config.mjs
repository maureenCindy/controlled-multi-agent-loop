import { defineConfig } from "astro/config";

// TP-034: static marketing site. `output: "static"` is Astro's default, but set explicitly
// per the issue's AC ("static output mode") — no server adapter, no SSR. All backend calls
// (signup, PayPal verification) happen client-side against the API's own base URL
// (PUBLIC_API_BASE_URL), not via an Astro server route.
export default defineConfig({
  output: "static",
  site: process.env.PUBLIC_SITE_URL ?? "https://tenderpulse.example",
});
