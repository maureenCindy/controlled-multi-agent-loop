import { describe, expect, it, vi } from "vitest";
import { isValidEmail, submitFreeSignup, submitProSignup } from "../src/lib/signup";

describe("isValidEmail", () => {
  it("accepts a normal email", () => {
    expect(isValidEmail("person@example.com")).toBe(true);
  });

  it("rejects an empty string", () => {
    expect(isValidEmail("")).toBe(false);
  });

  it("rejects a string with no @", () => {
    expect(isValidEmail("not-an-email")).toBe(false);
  });

  it("rejects a string with no domain", () => {
    expect(isValidEmail("person@")).toBe(false);
  });

  it("rejects a string with no local part", () => {
    expect(isValidEmail("@example.com")).toBe(false);
  });

  it("trims surrounding whitespace before checking", () => {
    expect(isValidEmail("  person@example.com  ")).toBe(true);
  });
});

describe("submitFreeSignup", () => {
  it("POSTs to /api/v1/subscribers with tier FREE (test case 1: valid Free signup)", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => ({ id: "abc", email: "a@b.com", tier: "FREE" }),
    });

    const result = await submitFreeSignup("a@b.com", fetchMock as unknown as typeof fetch);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toContain("/api/v1/subscribers");
    expect(url).not.toContain("/pro");
    expect(options.method).toBe("POST");
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(JSON.parse(options.body)).toEqual({ email: "a@b.com", tier: "FREE" });
    expect(result.ok).toBe(true);
    expect(result.status).toBe(201);
  });

  it("surfaces a non-ok response (e.g. duplicate email) without throwing", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({ error: "conflict", message: "Email already registered" }),
    });

    const result = await submitFreeSignup("dup@b.com", fetchMock as unknown as typeof fetch);

    expect(result.ok).toBe(false);
    expect(result.status).toBe(409);
    expect((result.body as { message: string }).message).toBe("Email already registered");
  });

  it("does not throw when the response has no JSON body", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => {
        throw new Error("no body");
      },
    });

    const result = await submitFreeSignup("a@b.com", fetchMock as unknown as typeof fetch);

    expect(result.ok).toBe(false);
    expect(result.body).toBeNull();
  });
});

describe("submitProSignup", () => {
  it("POSTs to /api/v1/subscribers/pro with the email and paypalSubscriptionId", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ id: "abc", tier: "PAID", paypalSubscriptionId: "I-SUB123" }),
    });

    const result = await submitProSignup(
      "pro@b.com",
      "I-SUB123",
      fetchMock as unknown as typeof fetch
    );

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toContain("/api/v1/subscribers/pro");
    expect(JSON.parse(options.body)).toEqual({
      email: "pro@b.com",
      paypalSubscriptionId: "I-SUB123",
    });
    expect(result.ok).toBe(true);
    expect(result.status).toBe(200);
  });
});
