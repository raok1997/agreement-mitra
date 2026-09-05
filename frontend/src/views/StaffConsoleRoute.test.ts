import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import App from "../App.vue";
import * as catalog from "../api/templateCatalog";
import * as staffQueue from "../api/staffQueue";
import * as auth from "../api/auth";
import { completeLogin, logout } from "../api/authStore";
import type { IdentityRole } from "../api/auth";

// Route-guard tests for /staff. The guard is presentational -- the backend refuses regardless -- but
// a customer must not be shown a console, told a queue size, or shown any order detail.
//
// The auth *store* stays real (App reads its reactive `auth`); only the network layer beneath it is
// mocked, so a login is established through the same code path the app uses.
vi.mock("../api/templateCatalog", () => ({ listTemplates: vi.fn() }));
vi.mock("../api/staffQueue", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/staffQueue")>();
  return { ...actual, listStampQueue: vi.fn(), uploadStampForEntry: vi.fn() };
});
vi.mock("../api/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/auth")>();
  return {
    ...actual,
    exchangeHandoff: vi.fn(),
    fetchMe: vi.fn(),
    logout: vi.fn(),
  };
});

const mockedCatalog = vi.mocked(catalog.listTemplates);
const mockedList = vi.mocked(staffQueue.listStampQueue);
const mockedExchange = vi.mocked(auth.exchangeHandoff);

/** Sign in as a role, through the real store, with only the network stubbed. */
async function signInAs(role: IdentityRole): Promise<void> {
  mockedExchange.mockResolvedValue({
    session: "session-value",
    me: {
      identityId: "id-1",
      displayName: "Tester",
      email: "t@example.com",
      role,
    },
  });
  await completeLogin("handoff");
}

/** Put the SPA on a path: App resolves its route from the URL by hand (no vue-router). */
function visit(path: string): void {
  window.history.replaceState({}, "", path);
}

beforeEach(async () => {
  mockedCatalog.mockReset();
  mockedCatalog.mockResolvedValue([]);
  mockedList.mockReset();
  mockedList.mockResolvedValue([]);
  mockedExchange.mockReset();
  await logout(); // start every case signed out
});

describe("/staff route guard", () => {
  it("shows a refusal and never loads the queue for an anonymous caller", async () => {
    visit("/staff");

    const wrapper = mount(App);
    await flushPromises();

    expect(wrapper.find('[data-testid="staff-forbidden"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="staff-console"]').exists()).toBe(false);
    // The queue is never even requested, so no count or entry can leak into the DOM.
    expect(mockedList).not.toHaveBeenCalled();
  });

  it("shows a refusal and never loads the queue for a CUSTOMER", async () => {
    visit("/staff");
    await signInAs("CUSTOMER");

    const wrapper = mount(App);
    await flushPromises();

    expect(wrapper.find('[data-testid="staff-forbidden"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="staff-console"]').exists()).toBe(false);
    expect(mockedList).not.toHaveBeenCalled();
  });

  it("renders the console for a STAFF caller", async () => {
    visit("/staff");
    await signInAs("STAFF");

    const wrapper = mount(App);
    await flushPromises();

    expect(wrapper.find('[data-testid="staff-console"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="staff-forbidden"]').exists()).toBe(
      false,
    );
    expect(mockedList).toHaveBeenCalled();
  });

  it("offers the console entry point in the header only to staff", async () => {
    visit("/start");
    await signInAs("CUSTOMER");
    const customerView = mount(App);
    await flushPromises();
    expect(
      customerView.find('[data-testid="nav-staff-console"]').exists(),
    ).toBe(false);

    await signInAs("STAFF");
    const staffView = mount(App);
    await flushPromises();
    expect(staffView.find('[data-testid="nav-staff-console"]').exists()).toBe(
      true,
    );
  });
});
