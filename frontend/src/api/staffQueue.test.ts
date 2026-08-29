import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  listStampQueue,
  uploadStampForEntry,
  StaffQueueHttpError,
  type StampQueueEntry,
} from "./staffQueue";

// API-client tests for the staff stamp queue. The upload assertion that matters is that the
// agreement reference sent to the server comes from the QUEUE ENTRY -- never from anything an
// operator typed -- because that is what removes the transcription step entirely.

const QUEUE = "/api/staff/estamp/queue";
const INTAKE = "/api/staff/estamp";

function entry(over: Partial<StampQueueEntry> = {}): StampQueueEntry {
  return {
    agreementId: "ag-1",
    trackingReference: "AM7K3QPW9Z4",
    propertyCity: "Bengaluru",
    agreementStartDate: "2026-01-01",
    awaitingSince: "2026-01-01T00:00:00Z",
    waitingSeconds: 7200,
    paymentState: null,
    ...over,
  };
}

function okJson(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

const fetchMock = vi.fn();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("staff queue api", () => {
  it("lists the queue from the staff endpoint", async () => {
    fetchMock.mockResolvedValue(okJson([entry()]));

    const rows = await listStampQueue();

    expect(fetchMock).toHaveBeenCalledWith(QUEUE, expect.anything());
    expect(rows).toHaveLength(1);
    expect(rows[0].trackingReference).toBe("AM7K3QPW9Z4");
  });

  it("surfaces the HTTP status so a 403 can be told apart from a failure", async () => {
    fetchMock.mockResolvedValue(new Response("", { status: 403 }));

    await expect(listStampQueue()).rejects.toBeInstanceOf(StaffQueueHttpError);
    await expect(listStampQueue()).rejects.toMatchObject({ status: 403 });
  });

  it("uploads against the entry's own reference, not a typed one", async () => {
    fetchMock.mockResolvedValue(
      okJson({
        agreementId: "ag-1",
        trackingReference: "AM7K3QPW9Z4",
        propertyCity: "Bengaluru",
        agreementStartDate: "2026-01-01",
        certificateNumberRedacted: "***234X",
      }),
    );
    const scan = new File([new Uint8Array([1, 2, 3])], "certificate.png", {
      type: "image/png",
    });

    const result = await uploadStampForEntry(entry(), scan, {
      certificateNumber: "IN-KA12345678901234X",
      issueDate: "2026-01-15",
      dutyAmount: "500.00",
      jurisdiction: "KA",
    });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe(INTAKE);
    expect(init.method).toBe("POST");
    const form = init.body as FormData;
    // The reference is the row's, carried through untouched.
    expect(form.get("agreementReference")).toBe("AM7K3QPW9Z4");
    expect(form.get("certificateNumber")).toBe("IN-KA12345678901234X");
    expect(form.get("scan")).toBeInstanceOf(File);
    // No Content-Type is set by hand: the browser must own the multipart boundary.
    expect(init.headers).not.toHaveProperty("Content-Type");
    // The echoed certificate number is already redacted server-side.
    expect(result.certificateNumberRedacted).toBe("***234X");
  });

  it("omits the optional certificate fields when they are blank", async () => {
    fetchMock.mockResolvedValue(okJson({}));
    const scan = new File([new Uint8Array([1])], "c.png", {
      type: "image/png",
    });

    await uploadStampForEntry(entry(), scan, {
      certificateNumber: "IN-KA12345678901234X",
      issueDate: "2026-01-15",
      dutyAmount: "500.00",
      jurisdiction: "KA",
    });

    const form = fetchMock.mock.calls[0][1].body as FormData;
    expect(form.get("descriptionOfDocument")).toBeNull();
    expect(form.get("purchasedBy")).toBeNull();
  });

  it("raises a typed error when the upload is refused", async () => {
    fetchMock.mockResolvedValue(new Response("", { status: 409 }));
    const scan = new File([new Uint8Array([1])], "c.png", {
      type: "image/png",
    });

    await expect(
      uploadStampForEntry(entry(), scan, {
        certificateNumber: "IN-KA12345678901234X",
        issueDate: "2026-01-15",
        dutyAmount: "500.00",
        jurisdiction: "KA",
      }),
    ).rejects.toMatchObject({ status: 409 });
  });

  it("carries the problem type so a payment refusal is not read as a certificate refusal", async () => {
    // With the payment gate enforced, "already stamped", "certificate already used" and "nobody has
    // paid" all arrive as 409. The status alone is no longer enough to tell an operator what to fix.
    fetchMock.mockResolvedValue(
      new Response(
        JSON.stringify({ type: "urn:agreementmitra:problem:payment-required" }),
        {
          status: 409,
          headers: { "Content-Type": "application/problem+json" },
        },
      ),
    );
    const scan = new File([new Uint8Array([1])], "c.png", {
      type: "image/png",
    });

    await expect(
      uploadStampForEntry(entry(), scan, {
        certificateNumber: "IN-KA12345678901234X",
        issueDate: "2026-01-15",
        dutyAmount: "500.00",
        jurisdiction: "KA",
      }),
    ).rejects.toMatchObject({ status: 409, paymentRequired: true });
  });

  it("does not mistake another 409 for a payment refusal", async () => {
    fetchMock.mockResolvedValue(
      new Response(
        JSON.stringify({
          type: "urn:agreementmitra:problem:certificate-already-used",
        }),
        {
          status: 409,
          headers: { "Content-Type": "application/problem+json" },
        },
      ),
    );
    const scan = new File([new Uint8Array([1])], "c.png", {
      type: "image/png",
    });

    await expect(
      uploadStampForEntry(entry(), scan, {
        certificateNumber: "IN-KA12345678901234X",
        issueDate: "2026-01-15",
        dutyAmount: "500.00",
        jurisdiction: "KA",
      }),
    ).rejects.toMatchObject({ paymentRequired: false });
  });
});
