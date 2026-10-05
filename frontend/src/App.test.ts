import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import App from "./App.vue";
import * as catalog from "./api/templateCatalog";
import * as templateForm from "./api/templateForm";
import * as documentPreview from "./api/documentPreview";
import * as client from "./api/client";
import type { TemplateSummary } from "./api/templateCatalog";
import type { FormSchema } from "./api/templateForm";

// Thin end-to-end through the App view-switch: pick a template -> fill required sections -> the live
// preview refreshes -> Save & continue creates + generates the draft. Only the network layer is mocked.
vi.mock("./api/templateCatalog", () => ({ listTemplates: vi.fn() }));
vi.mock("./api/templateForm", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api/templateForm")>();
  return { ...actual, getTemplateForm: vi.fn() };
});
vi.mock("./api/documentPreview", () => ({
  fetchDocumentPreviewHtml: vi.fn(),
  fetchDocumentPreviewPdf: vi.fn(),
}));
vi.mock("./api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api/client")>();
  return {
    ...actual,
    createAgreement: vi.fn(),
    generateAgreementDocument: vi.fn(),
  };
});

const mockedList = vi.mocked(catalog.listTemplates);
const mockedGetForm = vi.mocked(templateForm.getTemplateForm);
const mockedPreviewHtml = vi.mocked(documentPreview.fetchDocumentPreviewHtml);
const mockedCreate = vi.mocked(client.createAgreement);
const mockedGenerate = vi.mocked(client.generateAgreementDocument);

function catalogRows(): TemplateSummary[] {
  return [
    {
      id: "tg-res",
      name: "TG Residential Rental",
      description: "home rental",
      type: "residential",
      state: "TG",
      language: "en",
      version: 1,
    },
  ];
}

function schema(): FormSchema {
  return {
    dimensions: { state: "TG", type: "residential" },
    templateId: "rental-base",
    version: 1,
    contentHash: "h1",
    sections: [
      {
        title: "Parties",
        fields: [
          {
            key: "tenantName",
            label: "Tenant name",
            widget: "text",
            type: "text",
            required: true,
          },
          {
            key: "ownerName",
            label: "Owner name",
            widget: "text",
            type: "text",
            required: true,
          },
        ],
      },
      {
        title: "Property",
        fields: [
          {
            key: "propertyAddress",
            label: "Property address",
            widget: "textarea",
            type: "longtext",
            required: true,
          },
        ],
      },
    ],
  };
}

// Same as schema() but with one OPTIONAL section (Pets) presented in the Add-optional catalog.
function schemaWithOptional(): FormSchema {
  const base = schema();
  return {
    ...base,
    sections: [
      ...base.sections.map((s) => ({
        ...s,
        optional: false,
        renderKind: "keyvalue",
      })),
      {
        title: "Pets",
        optional: true,
        renderKind: "clauses",
        fields: [
          {
            key: "petNotes",
            label: "Pet notes",
            widget: "text",
            type: "text",
            required: false,
          },
        ],
      },
    ],
  };
}

describe("App end-to-end (pick -> fill -> preview -> save)", () => {
  beforeEach(() => {
    mockedList.mockReset().mockResolvedValue(catalogRows());
    mockedGetForm.mockReset().mockResolvedValue(schema());
    mockedPreviewHtml
      .mockReset()
      .mockResolvedValue("<html><body>preview</body></html>");
    mockedCreate.mockReset();
    mockedGenerate.mockReset();
    localStorage.clear();
    // App resolves its route from the path, and "/" is now the public marketing page.
    // These cases exercise the builder, so start them on the app route.
    window.history.replaceState({}, "", "/start");
  });

  it("picks a template, fills the form, and saves through the existing endpoints", async () => {
    mockedCreate.mockResolvedValue({
      id: "agr-9",
      trackingNumber: "AM-A5E4D9-010126",
      propertyAddress: "12 MG Road",
      monthlyRent: 0,
      securityDeposit: 0,
      startDate: "",
      endDate: "",
      durationMonths: 0,
      createdAt: "2026-07-12T00:00:00Z",
      signers: [],
    });
    mockedGenerate.mockResolvedValue();

    const wrapper = mount(App);
    await flushPromises();

    // Step 1: the picker is the entry step; capture is not mounted yet.
    expect(wrapper.find('[data-testid="picker-list"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="section-rail"]').exists()).toBe(false);

    // Step 2: choose the selectable (default) template -> capture mounts for (TG, residential).
    await wrapper.find('[data-testid="select-tg-res"]').trigger("click");
    await flushPromises();
    expect(mockedGetForm).toHaveBeenCalledWith("TG", "residential");
    expect(wrapper.find('[data-testid="section-rail"]').exists()).toBe(true);

    // Step 3: fill the required sections.
    async function fill(sectionId: string, values: Record<string, string>) {
      await wrapper
        .find(`[data-testid="section-${sectionId}"]`)
        .trigger("click");
      for (const [k, v] of Object.entries(values)) {
        await wrapper.find(`[data-testid="field-${k}"]`).setValue(v);
      }
      await wrapper.find('[data-testid="modal-save"]').trigger("click");
      await flushPromises();
    }
    await fill("parties", { tenantName: "Tara Sen", ownerName: "Asha Rao" });
    await fill("property", { propertyAddress: "12 MG Road" });

    // Step 4: the live preview refreshed with the picked dimensions.
    expect(mockedPreviewHtml).toHaveBeenCalled();
    expect(mockedPreviewHtml.mock.calls.at(-1)?.[1]).toEqual({
      state: "TG",
      type: "residential",
    });

    // Step 5: Save & continue creates then generates the draft.
    await wrapper.find('[data-testid="save-continue"]').trigger("click");
    await flushPromises();
    expect(mockedCreate).toHaveBeenCalledOnce();
    expect(mockedGenerate).toHaveBeenCalledWith("agr-9");
    expect(wrapper.find('[data-testid="save-ok"]').exists()).toBe(true);
  });

  it("pick -> fill mandatory -> add one optional -> preview reflects it -> Save enables (M4)", async () => {
    mockedGetForm.mockReset().mockResolvedValue(schemaWithOptional());
    mockedCreate.mockResolvedValue({
      id: "agr-10",
      trackingNumber: "AM-A5E410-010126",
      propertyAddress: "12 MG Road",
      monthlyRent: 0,
      securityDeposit: 0,
      startDate: "",
      endDate: "",
      durationMonths: 0,
      createdAt: "2026-07-12T00:00:00Z",
      signers: [],
    });
    mockedGenerate.mockResolvedValue();

    const wrapper = mount(App);
    await flushPromises();
    await wrapper.find('[data-testid="select-tg-res"]').trigger("click");
    await flushPromises();

    const save = () => wrapper.find('[data-testid="save-continue"]');
    async function fill(sectionId: string, values: Record<string, string>) {
      await wrapper
        .find(`[data-testid="section-${sectionId}"]`)
        .trigger("click");
      for (const [k, v] of Object.entries(values)) {
        await wrapper.find(`[data-testid="field-${k}"]`).setValue(v);
      }
      await wrapper.find('[data-testid="modal-save"]').trigger("click");
      await flushPromises();
    }

    // Save stays disabled until the LAST mandatory section completes.
    expect(save().attributes("disabled")).toBeDefined();
    await fill("parties", { tenantName: "Tara Sen", ownerName: "Asha Rao" });
    expect(save().attributes("disabled")).toBeDefined();
    await fill("property", { propertyAddress: "12 MG Road" });
    expect(save().attributes("disabled")).toBeUndefined();

    // Add one optional section -> the subsequent preview POST carries its title in activeSections.
    mockedPreviewHtml.mockClear();
    await wrapper.find('[data-testid="add-optional-pets"]').trigger("click");
    await new Promise((r) => setTimeout(r, 650)); // debounced (~600ms) preview refresh
    await flushPromises();
    expect(wrapper.find('[data-testid="active-optional-pets"]').exists()).toBe(
      true,
    );
    expect(mockedPreviewHtml.mock.calls.at(-1)?.[2]).toContain("Pets");

    // Save & continue still fires the existing create + generate-as-draft calls.
    await save().trigger("click");
    await flushPromises();
    expect(mockedCreate).toHaveBeenCalledOnce();
    expect(mockedGenerate).toHaveBeenCalledWith("agr-10");
    expect(wrapper.find('[data-testid="save-ok"]').exists()).toBe(true);
  });

  it("Change template returns to the picker", async () => {
    const wrapper = mount(App);
    await flushPromises();
    await wrapper.find('[data-testid="select-tg-res"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-testid="section-rail"]').exists()).toBe(true);

    await wrapper.find('[data-testid="change-template"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-testid="picker-list"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="section-rail"]').exists()).toBe(false);
  });
});

// The path switch that splits the public site from the app. Router-less by design
// (flow-journal 8.3), so these assertions are what keeps the hand-rolled version honest.
describe("App route switch", () => {
  // jsdom runs history.back() as a queued task, so a fixed timeout races it -- and a
  // late-firing popstate would leak into the next test's location. Await the event.
  function goBack(): Promise<void> {
    return new Promise((resolve) => {
      window.addEventListener("popstate", () => resolve(), { once: true });
      window.history.back();
    });
  }

  beforeEach(() => {
    mockedList.mockReset().mockResolvedValue(catalogRows());
    mockedGetForm.mockReset().mockResolvedValue(schema());
    localStorage.clear();
  });

  it('serves the marketing page at "/" without touching the API', async () => {
    window.history.replaceState({}, "", "/");
    const wrapper = mount(App);
    await flushPromises();

    expect(wrapper.find('[data-testid="hero-start"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="picker-list"]').exists()).toBe(false);
    // The landing page must render even when the backend is down.
    expect(mockedList).not.toHaveBeenCalled();
    // The operator disclosure, scoped to the footer itself.
    expect(wrapper.get("footer").text()).toContain(
      "AgreementMitra is a service of KAVISAT TEK LABS LLP.",
    );
  });

  it("enters the builder at /start from a landing CTA, and back returns to the page", async () => {
    window.history.replaceState({}, "", "/");
    const wrapper = mount(App);
    await flushPromises();

    await wrapper.find('[data-testid="hero-start"]').trigger("click");
    await flushPromises();
    expect(window.location.pathname).toBe("/start");
    expect(wrapper.find('[data-testid="picker-list"]').exists()).toBe(true);

    // Entering the app is a real history entry, so Back must land on the marketing page.
    await goBack();
    await flushPromises();
    expect(window.location.pathname).toBe("/");
    expect(wrapper.find('[data-testid="hero-start"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it("opens the landing page's Questions tab from a shared /#faq link", async () => {
    // App routes on pathname only, so the hash survives to the landing page, which opens its tab.
    window.history.replaceState({}, "", "/#faq");
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    const wrapper = mount(App, { attachTo: document.body });
    try {
      await flushPromises();

      expect(wrapper.get("#tab-faq").attributes("aria-selected")).toBe("true");
      expect(wrapper.get("#faq").attributes("hidden")).toBeUndefined();
      expect(scrollIntoView).toHaveBeenCalled();
      expect(mockedList).not.toHaveBeenCalled();

      await wrapper.find('[data-testid="hero-start"]').trigger("click");
      await flushPromises();
      expect(window.location.pathname).toBe("/start");
      expect(wrapper.find('[data-testid="picker-list"]').exists()).toBe(true);
    } finally {
      wrapper.unmount();
      Reflect.deleteProperty(Element.prototype, "scrollIntoView");
    }
  });

  it("mounts the OAuth callback on /auth/callback rather than the marketing page", async () => {
    window.history.replaceState({}, "", "/auth/callback");
    const wrapper = mount(App);
    await flushPromises();

    expect(wrapper.find('[data-testid="hero-start"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="picker-list"]').exists()).toBe(false);
    // No handoff in the fragment, so the callback view settles on its error state.
    expect(wrapper.text()).toContain("complete sign-in");
  });

  it("serves the terms of service at /terms, chrome-free and without touching the API", async () => {
    // The in-product disclaimer links here. A reader following it is mid-flow and may have no
    // session, so /terms must render on its own -- no app header, no picker, no backend call.
    window.history.replaceState({}, "", "/terms");
    const wrapper = mount(App);
    await flushPromises();

    expect(wrapper.find('[data-testid="terms-of-service"]').exists()).toBe(
      true,
    );
    expect(wrapper.find('[data-testid="terms-draft-banner"]').exists()).toBe(
      true,
    );
    expect(wrapper.find('[data-testid="picker-list"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="hero-start"]').exists()).toBe(false);
    expect(mockedList).not.toHaveBeenCalled();
    // The shared footer, scoped to the <footer>: the name also appears in §1 and Operator details.
    expect(wrapper.get("footer").text()).toContain(
      "AgreementMitra is a service of KAVISAT TEK LABS LLP.",
    );
    expect(wrapper.find('footer a[href="/terms"]').exists()).toBe(true);
    expect(wrapper.find('footer a[href^="mailto:"]').exists()).toBe(true);
  });

  it("returns from /terms to wherever the reader came from", async () => {
    window.history.replaceState({}, "", "/");
    const wrapper = mount(App);
    await flushPromises();

    window.history.pushState({}, "", "/terms");
    window.dispatchEvent(new PopStateEvent("popstate"));
    await flushPromises();
    expect(wrapper.find('[data-testid="terms-of-service"]').exists()).toBe(
      true,
    );

    // "Back" is the browser's own back, so await the popstate rather than triggering a second one.
    const popped = new Promise<void>((resolve) =>
      window.addEventListener("popstate", () => resolve(), { once: true }),
    );
    await wrapper.find('[data-testid="terms-back"]').trigger("click");
    await popped;
    await flushPromises();
    expect(window.location.pathname).toBe("/");
    wrapper.unmount();
  });

  it("falls through unknown deep links to the app, not the marketing page", async () => {
    window.history.replaceState({}, "", "/start/anything");
    const wrapper = mount(App);
    await flushPromises();

    expect(wrapper.find('[data-testid="picker-list"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="hero-start"]').exists()).toBe(false);
  });
});

// The emailed agreement link (agreement-status-link-page): it lands on the status view, not the
// edit form, and the address bar keeps the link so a reload or a bookmark comes back here.
vi.mock("./api/agreements", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api/agreements")>();
  return { ...actual, getAgreement: vi.fn(), listMyAgreements: vi.fn() };
});
vi.mock("./api/payments", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api/payments")>();
  return { ...actual, getPaymentProgress: vi.fn() };
});
vi.mock("./api/signingProgress", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api/signingProgress")>();
  return { ...actual, getSigningProgress: vi.fn() };
});

describe("App agreement link", () => {
  const LINK = "/agreement/3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";

  beforeEach(async () => {
    const agreements = await import("./api/agreements");
    const payments = await import("./api/payments");
    const signingProgress = await import("./api/signingProgress");
    vi.mocked(agreements.getAgreement).mockReset();
    vi.mocked(payments.getPaymentProgress).mockReset().mockResolvedValue({
      agreementId: "ag-1",
      paymentState: "PAID",
      orderStatus: "paid",
      amountMinorUnits: 49900,
      currency: "INR",
    });
    vi.mocked(signingProgress.getSigningProgress)
      .mockReset()
      .mockResolvedValue({
        agreementId: "ag-1",
        status: "IN_PROGRESS",
        stage: "AWAITING_STAMP",
        terminal: false,
        signedDocumentReady: false,
        parties: [],
      });
  });

  it("mounts the status view, not the capture form, and keeps the link in the address bar", async () => {
    const agreements = await import("./api/agreements");
    vi.mocked(agreements.getAgreement).mockResolvedValue({
      id: "ag-1",
      trackingNumber: "AM3G3VXSAKD",
      propertyAddress: "12 MG Road",
      monthlyRent: 25000,
      securityDeposit: 50000,
      startDate: "2026-01-01",
      endDate: "2026-12-01",
      durationMonths: 11,
      createdAt: "2026-01-01T00:00:00Z",
      signers: [],
      state: "TG",
      type: "residential",
    });
    window.history.replaceState({}, "", LINK);
    const wrapper = mount(App);
    await flushPromises();

    expect(wrapper.find('[data-testid="agreement-status"]').exists()).toBe(
      true,
    );
    expect(wrapper.find('[data-testid="status-reference"]').text()).toBe(
      "AM3G3VXSAKD",
    );
    expect(wrapper.find('[data-testid="capture-form"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain("Edit agreement");
    expect(window.location.pathname).toBe(LINK);
    wrapper.unmount();
  });

  it("keeps the claimed-or-unknown message and its sign-in button", async () => {
    const agreements = await import("./api/agreements");
    vi.mocked(agreements.getAgreement).mockRejectedValue(
      new agreements.AgreementHttpError(404),
    );
    window.history.replaceState({}, "", LINK);
    const wrapper = mount(App);
    await flushPromises();

    expect(wrapper.find('[data-testid="agreement-status"]').exists()).toBe(
      false,
    );
    expect(wrapper.text()).toContain("saved to an account, sign in");
    expect(wrapper.text()).toContain("Sign in");
    expect(window.location.pathname).toBe(LINK);
    wrapper.unmount();
  });
});

// The header's "My agreements" switches the in-app view; from /staff that view is not on the page
// at all, so the switch must also return to the app route or the click does nothing visible.
// The boot /me defaults to "no session" (401): left resolving undefined, init() would read every
// anonymous test as signed in.
vi.mock("./api/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api/auth")>();
  return {
    ...actual,
    exchangeHandoff: vi.fn(),
    fetchMe: vi.fn(() => Promise.reject(new actual.AuthHttpError(401))),
    logout: vi.fn(),
  };
});
vi.mock("./api/staffQueue", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api/staffQueue")>();
  return { ...actual, listStampQueue: vi.fn() };
});

describe("App header navigation from the staff console", () => {
  beforeEach(async () => {
    const auth = await import("./api/auth");
    const agreements = await import("./api/agreements");
    const staffQueue = await import("./api/staffQueue");
    vi.mocked(auth.exchangeHandoff)
      .mockReset()
      .mockResolvedValue({
        me: {
          identityId: "id-1",
          displayName: "Staff",
          email: null,
          role: "STAFF",
        },
      });
    vi.mocked(auth.logout).mockReset().mockResolvedValue();
    vi.mocked(agreements.listMyAgreements).mockReset().mockResolvedValue([]);
    vi.mocked(staffQueue.listStampQueue).mockReset().mockResolvedValue([]);
    mockedList.mockReset().mockResolvedValue(catalogRows());
    const store = await import("./api/authStore");
    await store.completeLogin("handoff");
  });

  it("opens My agreements from /staff", async () => {
    window.history.replaceState({}, "", "/staff");
    const wrapper = mount(App);
    await flushPromises();
    expect(wrapper.find('[data-testid="my-agreements"]').exists()).toBe(false);

    await wrapper.find('[data-testid="nav-my-agreements"]').trigger("click");
    await flushPromises();

    expect(window.location.pathname).toBe("/start");
    expect(wrapper.find('[data-testid="my-agreements"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="staff-forbidden"]').exists()).toBe(
      false,
    );
    wrapper.unmount();
    const store = await import("./api/authStore");
    await store.logout();
  });
});

// The session is an HttpOnly cookie, so a reload knows nothing until /me answers. Each test re-imports
// the app (vi.resetModules) so the auth store's module-load `ready` promise starts pending.
describe("App reload (cookie session)", () => {
  const ME_R = {
    identityId: "id-r",
    displayName: "Restored User",
    email: null,
    role: "CUSTOMER" as const,
  };

  async function reloadApp(path: string) {
    vi.resetModules();
    const auth = await import("./api/auth");
    const catalogR = await import("./api/templateCatalog");
    vi.mocked(catalogR.listTemplates).mockResolvedValue(catalogRows());
    const { default: ReloadedApp } = await import("./App.vue");
    window.history.replaceState({}, "", path);
    return { auth, ReloadedApp };
  }

  function pending<T>() {
    let resolve!: (v: T) => void;
    let reject!: (e: unknown) => void;
    const promise = new Promise<T>((res, rej) => {
      resolve = res;
      reject = rej;
    });
    return { promise, resolve, reject };
  }

  afterEach(() => sessionStorage.clear());

  it("a reload with /me 200 shows the signed-in header", async () => {
    sessionStorage.setItem("am.session", "legacy-value");
    const { auth, ReloadedApp } = await reloadApp("/start");
    vi.mocked(auth.fetchMe).mockResolvedValue(ME_R);
    const wrapper = mount(ReloadedApp);
    await flushPromises();

    expect(auth.fetchMe).toHaveBeenCalledWith();
    expect(sessionStorage.getItem("am.session")).toBeNull();
    expect(wrapper.find('[data-testid="nav-my-agreements"]').exists()).toBe(
      true,
    );
    expect(wrapper.text()).toContain("Restored User");
    wrapper.unmount();
  });

  it("renders no account control before /me resolves", async () => {
    const { auth, ReloadedApp } = await reloadApp("/start");
    const me = pending<typeof ME_R>();
    vi.mocked(auth.fetchMe).mockReturnValue(me.promise);
    const wrapper = mount(ReloadedApp);
    await flushPromises();

    expect(wrapper.text()).not.toContain("Sign in with Google");
    expect(wrapper.find('[data-testid="nav-my-agreements"]').exists()).toBe(
      false,
    );

    me.reject(new auth.AuthHttpError(401));
    await flushPromises();
    expect(wrapper.text()).toContain("Sign in with Google");
    wrapper.unmount();
  });

  it("My agreements requested while /me is pending waits, then opens for a signed-in user", async () => {
    const { auth, ReloadedApp } = await reloadApp("/start");
    const agreements = await import("./api/agreements");
    vi.mocked(agreements.listMyAgreements).mockResolvedValue([]);
    const me = pending<typeof ME_R>();
    vi.mocked(auth.fetchMe).mockReturnValue(me.promise);
    const wrapper = mount(ReloadedApp, {
      global: { stubs: { TemplatePicker: true, CaptureForm: true } },
    });
    await flushPromises();

    // A save-to-account before the boot check answers routes through showMyAgreements().
    wrapper
      .findComponent({ name: "TemplatePicker" })
      .vm.$emit("select", { state: "TG", type: "residential" });
    await flushPromises();
    wrapper.findComponent({ name: "CaptureForm" }).vm.$emit("saved-to-account");
    await flushPromises();
    expect(wrapper.find('[data-testid="my-agreements"]').exists()).toBe(false);

    // Had it decided early, it would have sent the user to Google and never shown the list.
    me.resolve(ME_R);
    await flushPromises();
    expect(wrapper.find('[data-testid="my-agreements"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it("an agreement link opened while /me is pending does not ask a signed-in user to sign in", async () => {
    const { auth, ReloadedApp } = await reloadApp(
      "/agreement/3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
    );
    const agreements = await import("./api/agreements");
    vi.mocked(agreements.getAgreement).mockRejectedValue(
      new agreements.AgreementHttpError(404),
    );
    const me = pending<typeof ME_R>();
    vi.mocked(auth.fetchMe).mockReturnValue(me.promise);
    const wrapper = mount(ReloadedApp);
    await flushPromises();

    // The link read failed, but the sign-in decision waits for /me.
    expect(wrapper.text()).not.toContain("This link did not open");

    me.resolve(ME_R);
    await flushPromises();
    expect(wrapper.text()).toContain("This link did not open");
    expect(wrapper.find("section button.bg-slate-900").exists()).toBe(false);
    wrapper.unmount();
  });

  it("the staff console shows loading, not the refusal, while /me is pending", async () => {
    const { auth, ReloadedApp } = await reloadApp("/staff");
    const staffQueue = await import("./api/staffQueue");
    vi.mocked(staffQueue.listStampQueue).mockResolvedValue([]);
    const me = pending<{
      identityId: string;
      displayName: string;
      email: null;
      role: "STAFF";
    }>();
    vi.mocked(auth.fetchMe).mockReturnValue(me.promise);
    const wrapper = mount(ReloadedApp);
    await flushPromises();

    expect(wrapper.find('[data-testid="staff-loading"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="staff-forbidden"]').exists()).toBe(
      false,
    );

    me.resolve({
      identityId: "s",
      displayName: "Staff",
      email: null,
      role: "STAFF",
    });
    await flushPromises();
    expect(wrapper.find('[data-testid="staff-loading"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="staff-forbidden"]').exists()).toBe(
      false,
    );
    wrapper.unmount();
  });

  it("on the callback route, an exchange resolving before a pending boot /me ends signed in", async () => {
    const { auth, ReloadedApp } = await reloadApp("/auth/callback");
    window.history.replaceState({}, "", "/auth/callback#handoff=h-1");
    const me = pending<typeof ME_R>();
    vi.mocked(auth.fetchMe).mockReturnValue(me.promise);
    vi.mocked(auth.exchangeHandoff).mockResolvedValue({ me: ME_R });
    const wrapper = mount(ReloadedApp);
    await flushPromises();

    me.reject(new auth.AuthHttpError(403)); // the boot /me carried the pre-login cookie
    await flushPromises();

    expect(wrapper.text()).toContain("Restored User");
    expect(wrapper.find('[data-testid="nav-my-agreements"]').exists()).toBe(
      true,
    );
    wrapper.unmount();
  });
});
