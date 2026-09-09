import { readFileSync, readdirSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const appRoot = resolve(import.meta.dirname, "..");
const homepage = readFileSync(resolve(appRoot, "src/pages/index.astro"), "utf8");

describe("TP-140 approved homepage contract", () => {
  it("keeps only the homepage and privacy note as Astro routes", () => {
    expect(readdirSync(resolve(appRoot, "src/pages")).sort()).toEqual([
      "index.astro",
      "privacy.astro",
    ]);
  });

  it.each(["alertSetup", "howItWorks", "marketInsights", "testimonials", "pricing", "contact"])(
    "includes the %s section",
    (sectionId) => {
      expect(homepage).toContain(`id="${sectionId}"`);
    }
  );

  it.each([
    ["free", "Weekly email — Free"],
    ["pro", "Real-time alerts"],
    ["max", "Market intelligence — Max"],
  ])("includes the %s plan", (plan, label) => {
    expect(homepage).toContain(`value="${plan}"`);
    expect(homepage).toContain(label);
  });

  it("keeps both homepage forms explicitly demo-only", () => {
    expect(homepage).toContain("Demo form — no signup information will be sent.");
    expect(homepage).toContain("Demo only—your message was not sent.");
    expect(homepage).not.toContain("fetch(");
    expect(homepage).not.toContain("/api/v1/");
  });

  it("links the Max insight preview and official tender source safely", () => {
    expect(homepage).toContain('href="/max-portal-preview.html"');
    expect(homepage).toContain('href="https://egp.praz.org.zw/"');
    expect(homepage).toContain('rel="noopener noreferrer"');
  });

  it("ships the approved public assets", () => {
    for (const asset of [
      "tenderbell-wordmark-light.svg",
      "tenderbell-wordmark-dark.svg",
      "tenderbell-bell-mark.svg",
      "max-portal-preview.html",
    ]) {
      expect(() => readFileSync(resolve(appRoot, "public", asset))).not.toThrow();
    }
  });
});
