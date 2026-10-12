import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { fetchStaffOrders, StaffOrdersHttpError } from "./staffOrders";

const ORDERS = "/api/staff/payment-orders";

const fetchMock = vi.fn();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("staff orders api", () => {
  it("reads both groups from the staff orders endpoint with a GET", async () => {
    const body = {
      stuck: { entries: [], truncated: false },
      notPaid: { entries: [], truncated: true },
    };
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    const orders = await fetchStaffOrders();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe(ORDERS);
    expect((init?.method ?? "GET").toUpperCase()).toBe("GET");
    expect(orders).toEqual(body);
  });

  it("turns a refusal into an error carrying the status", async () => {
    fetchMock.mockResolvedValue(new Response("", { status: 403 }));

    await expect(fetchStaffOrders()).rejects.toBeInstanceOf(
      StaffOrdersHttpError,
    );
    await expect(fetchStaffOrders()).rejects.toMatchObject({ status: 403 });
  });

  it("turns a server failure into an error too", async () => {
    fetchMock.mockResolvedValue(new Response("", { status: 500 }));

    await expect(fetchStaffOrders()).rejects.toMatchObject({ status: 500 });
  });
});
