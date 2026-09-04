import { afterEach, describe, expect, it, vi } from "vitest";
import { loadPayPalSdk, payPalSdkUrl, renderPayPalSubscribeButton } from "../src/lib/paypal";

describe("payPalSdkUrl", () => {
  it("includes the client id, vault=true and intent=subscription", () => {
    const url = payPalSdkUrl("ABC123");
    expect(url).toContain("https://www.paypal.com/sdk/js?");
    expect(url).toContain("client-id=ABC123");
    expect(url).toContain("vault=true");
    expect(url).toContain("intent=subscription");
  });
});

describe("loadPayPalSdk", () => {
  afterEach(() => {
    delete (globalThis as any).paypal;
  });

  it("injects a script tag with the correct src and resolves once it loads", async () => {
    const scriptEl: any = {};
    const doc: any = {
      createElement: vi.fn(() => scriptEl),
      head: { appendChild: vi.fn() },
    };

    const promise = loadPayPalSdk("CID123", doc as unknown as Document);

    expect(doc.createElement).toHaveBeenCalledWith("script");
    expect(scriptEl.src).toContain("client-id=CID123");
    expect(doc.head.appendChild).toHaveBeenCalledWith(scriptEl);

    (globalThis as any).paypal = { Buttons: vi.fn() };
    scriptEl.onload();

    const sdk = await promise;
    expect(sdk).toBe((globalThis as any).paypal);
  });

  it("resolves immediately without injecting a script if the SDK is already loaded", async () => {
    const alreadyLoaded = { Buttons: vi.fn() };
    (globalThis as any).paypal = alreadyLoaded;
    const doc: any = { createElement: vi.fn(), head: { appendChild: vi.fn() } };

    const sdk = await loadPayPalSdk("CID123", doc as unknown as Document);

    expect(sdk).toBe(alreadyLoaded);
    expect(doc.createElement).not.toHaveBeenCalled();
  });
});

/**
 * Test case 5 (issue #40): can't test a real PayPal approval end-to-end without real
 * credentials, so these exercise the button-render wiring and the onApprove handler against a
 * stubbed/mocked PayPal SDK instead — proving our own code calls the SDK and the backend
 * correctly, without needing a live PayPal account.
 */
describe("renderPayPalSubscribeButton", () => {
  function fakeSdk() {
    const render = vi.fn();
    const Buttons = vi.fn(() => ({ render }));
    return { Buttons, render };
  }

  it("renders a button into the given container (test case 5: button renders)", () => {
    const { Buttons, render } = fakeSdk();

    renderPayPalSubscribeButton({ Buttons }, "#paypal-button-container", "PLAN-1", () => "a@b.com", {
      onSuccess: vi.fn(),
      onError: vi.fn(),
    });

    expect(Buttons).toHaveBeenCalledTimes(1);
    expect(render).toHaveBeenCalledWith("#paypal-button-container");
  });

  it("createSubscription creates a PayPal subscription against the configured plan id", () => {
    const { Buttons } = fakeSdk();

    renderPayPalSubscribeButton({ Buttons }, "#c", "PLAN-42", () => "a@b.com", {
      onSuccess: vi.fn(),
      onError: vi.fn(),
    });

    const config = Buttons.mock.calls[0][0];
    const create = vi.fn();
    config.createSubscription(undefined, { subscription: { create } });

    expect(create).toHaveBeenCalledWith({ plan_id: "PLAN-42" });
  });

  it("onApprove POSTs the subscription id and current email to the backend, then calls onSuccess", async () => {
    const { Buttons } = fakeSdk();
    const submitPro = vi.fn().mockResolvedValue({ ok: true, status: 200, body: { tier: "PAID" } });
    const onSuccess = vi.fn();
    const onError = vi.fn();

    renderPayPalSubscribeButton(
      { Buttons },
      "#c",
      "PLAN-1",
      () => "pro@example.com",
      { onSuccess, onError },
      submitPro
    );

    const config = Buttons.mock.calls[0][0];
    await config.onApprove({ subscriptionID: "I-SUB-999" });

    expect(submitPro).toHaveBeenCalledWith("pro@example.com", "I-SUB-999");
    expect(onSuccess).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });

  it("onApprove calls onError with the backend's message when verification fails", async () => {
    const { Buttons } = fakeSdk();
    const submitPro = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      body: { message: "PayPal subscription 'I-FAKE' was not found" },
    });
    const onError = vi.fn();

    renderPayPalSubscribeButton(
      { Buttons },
      "#c",
      "PLAN-1",
      () => "pro@example.com",
      { onSuccess: vi.fn(), onError },
      submitPro
    );

    const config = Buttons.mock.calls[0][0];
    await config.onApprove({ subscriptionID: "I-FAKE" });

    expect(onError).toHaveBeenCalledWith("PayPal subscription 'I-FAKE' was not found");
  });

  it("onApprove calls onError (and never calls the backend) when getEmail rejects an invalid email", async () => {
    const { Buttons } = fakeSdk();
    const submitPro = vi.fn();
    const onError = vi.fn();

    renderPayPalSubscribeButton(
      { Buttons },
      "#c",
      "PLAN-1",
      () => {
        throw new Error("Enter a valid email address before subscribing.");
      },
      { onSuccess: vi.fn(), onError },
      submitPro
    );

    const config = Buttons.mock.calls[0][0];
    await config.onApprove({ subscriptionID: "I-SUB" });

    expect(submitPro).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledWith("Enter a valid email address before subscribing.");
  });

  it("the SDK's own onError callback surfaces a friendly message", () => {
    const { Buttons } = fakeSdk();
    const onError = vi.fn();

    renderPayPalSubscribeButton({ Buttons }, "#c", "PLAN-1", () => "a@b.com", {
      onSuccess: vi.fn(),
      onError,
    });

    const config = Buttons.mock.calls[0][0];
    config.onError(new Error("paypal sdk internal error"));

    expect(onError).toHaveBeenCalledWith(
      "PayPal was unable to complete the subscription. Please try again."
    );
  });
});
