import { describe, expect, it } from "vitest";
import { resolveWhatsAppFlag, WHATSAPP_NOTIFICATIONS_ENABLED } from "../src/lib/featureFlags";

describe("resolveWhatsAppFlag", () => {
  it("is true when the raw value is exactly 'true'", () => {
    expect(resolveWhatsAppFlag("true")).toBe(true);
  });

  it("defaults to false when the raw value is undefined (unset env var)", () => {
    expect(resolveWhatsAppFlag(undefined)).toBe(false);
  });

  it("is false for the literal string 'false'", () => {
    expect(resolveWhatsAppFlag("false")).toBe(false);
  });

  it("is false for an empty string", () => {
    expect(resolveWhatsAppFlag("")).toBe(false);
  });

  it("is false for a wrong-case value like 'TRUE'", () => {
    expect(resolveWhatsAppFlag("TRUE")).toBe(false);
  });

  it("is false for an arbitrary truthy-looking value like '1'", () => {
    expect(resolveWhatsAppFlag("1")).toBe(false);
  });

  it("is false for a value with surrounding whitespace like ' true '", () => {
    expect(resolveWhatsAppFlag(" true ")).toBe(false);
  });
});

describe("WHATSAPP_NOTIFICATIONS_ENABLED", () => {
  it("is a boolean (resolved once at module load from import.meta.env)", () => {
    expect(typeof WHATSAPP_NOTIFICATIONS_ENABLED).toBe("boolean");
  });
});
