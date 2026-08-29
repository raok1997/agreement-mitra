import { describe, it, expect, vi, beforeEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import StaffConsole from "./StaffConsole.vue";
import * as staffQueue from "../api/staffQueue";
import type { StampQueueEntry } from "../api/staffQueue";

// Component tests for the staff fulfilment console. The load-bearing assertion is that an upload
// carries the ROW's tracking reference: the console exists so nobody re-types it, which removes the
// transcription mistake the check character otherwise has to catch.
vi.mock("../api/staffQueue", async () => {
  const actual = await vi.importActual<typeof staffQueue>("../api/staffQueue");
  return {
    ...actual,
    listStampQueue: vi.fn(),
    uploadStampForEntry: vi.fn(),
  };
});

const mockedList = vi.mocked(staffQueue.listStampQueue);
const mockedUpload = vi.mocked(staffQueue.uploadStampForEntry);

function entry(over: Partial<StampQueueEntry> = {}): StampQueueEntry {
  return {
    agreementId: "ag-1",
    trackingReference: "AM7K3QPW9Z4",
    templateName: "Karnataka Residential Rental Agreement",
    templateState: "KA",
    parties: [
      { role: "OWNER", name: "Asha Rao", fatherName: "Krishna Rao" },
      { role: "TENANT", name: "Bilal Khan", fatherName: "Imran Khan" },
    ],
    propertyCity: "Bengaluru",
    agreementStartDate: "2026-01-01",
    awaitingSince: "2026-01-01T00:00:00Z",
    waitingSeconds: 7200,
    paymentState: null,
    ...over,
  };
}

beforeEach(() => {
  mockedList.mockReset();
  mockedUpload.mockReset();
});

describe("StaffConsole", () => {
  it("lists outstanding orders with their reference, city and waiting time", async () => {
    mockedList.mockResolvedValue([
      entry(),
      entry({
        agreementId: "ag-2",
        trackingReference: "AM4M8TQXR5B",
        waitingSeconds: 120,
      }),
    ]);

    const wrapper = mount(StaffConsole);
    await flushPromises();

    expect(wrapper.find('[data-testid="queue-reference-ag-1"]').text()).toBe(
      "AM7K3QPW9Z4",
    );
    expect(wrapper.find('[data-testid="queue-waiting-ag-1"]').text()).toContain(
      "2h",
    );
    expect(wrapper.find('[data-testid="queue-waiting-ag-2"]').text()).toContain(
      "2m",
    );
    expect(wrapper.text()).toContain("Bengaluru");
  });

  it("shows an empty state when nothing is waiting", async () => {
    mockedList.mockResolvedValue([]);

    const wrapper = mount(StaffConsole);
    await flushPromises();

    expect(wrapper.find('[data-testid="queue-empty"]').exists()).toBe(true);
  });

  it("uploads using the row's reference so nothing is re-typed", async () => {
    mockedList.mockResolvedValue([entry()]);
    mockedUpload.mockResolvedValue({
      agreementId: "ag-1",
      trackingReference: "AM7K3QPW9Z4",
      propertyCity: "Bengaluru",
      agreementStartDate: "2026-01-01",
      certificateNumberRedacted: "***234X",
    });

    const wrapper = mount(StaffConsole);
    await flushPromises();

    await wrapper.find('[data-testid="queue-upload-ag-1"]').trigger("click");
    // The form offers NO agreement-reference input at all -- there is nothing to mistype.
    expect(wrapper.find('[data-testid="queue-form-ag-1"]').exists()).toBe(true);
    expect(wrapper.find('input[name="agreementReference"]').exists()).toBe(
      false,
    );

    await wrapper
      .find('[data-testid="field-certificate-number"]')
      .setValue("IN-KA1234567890123");
    await wrapper
      .find('[data-testid="field-issue-date"]')
      .setValue("2026-01-15");
    await wrapper.find('[data-testid="field-duty-amount"]').setValue("500.00");
    await wrapper.find('[data-testid="field-jurisdiction"]').setValue("KA");

    // Attach a scan the way the browser would.
    const file = new File([new Uint8Array([1, 2, 3])], "certificate.png", {
      type: "image/png",
    });
    const input = wrapper.find('[data-testid="field-scan"]')
      .element as HTMLInputElement;
    Object.defineProperty(input, "files", {
      value: [file],
      configurable: true,
    });
    await wrapper.find('[data-testid="field-scan"]').trigger("change");

    mockedList.mockResolvedValue([]); // the stamped order leaves the queue
    await wrapper.find('[data-testid="queue-form-ag-1"]').trigger("submit");
    await flushPromises();

    expect(mockedUpload).toHaveBeenCalledTimes(1);
    const [passedEntry, passedScan, certificate] = mockedUpload.mock.calls[0];
    expect(passedEntry.trackingReference).toBe("AM7K3QPW9Z4");
    expect(passedScan).toBe(file);
    expect(certificate.certificateNumber).toBe("IN-KA1234567890123");
    // The confirmation echoes only the redacted certificate number.
    expect(wrapper.find('[data-testid="queue-result"]').text()).toContain(
      "***234X",
    );
    expect(wrapper.find('[data-testid="queue-result"]').text()).not.toContain(
      "1234567890123",
    );
    // ...and the order is gone from the queue.
    expect(wrapper.find('[data-testid="queue-empty"]').exists()).toBe(true);
  });

  it("sends for signature by default and says so, without a second operator step", async () => {
    mockedList.mockResolvedValue([entry()]);
    mockedUpload.mockResolvedValue({
      agreementId: "ag-1",
      trackingReference: "AM7K3QPW9Z4",
      propertyCity: "Bengaluru",
      agreementStartDate: "2026-01-01",
      certificateNumberRedacted: "***234X",
      signingInitiated: true,
      signingNotStartedReason: null,
    });

    const wrapper = await filledForm();
    // Checked when the form opens: attaching and sending is the normal fulfilment step.
    const box = wrapper.find('[data-testid="field-initiate-signing"]')
      .element as HTMLInputElement;
    expect(box.checked).toBe(true);

    mockedList.mockResolvedValue([]);
    await wrapper.find('[data-testid="queue-form-ag-1"]').trigger("submit");
    await flushPromises();

    expect(mockedUpload.mock.calls[0][2].initiateSigning).toBe(true);
    expect(wrapper.find('[data-testid="queue-result"]').text()).toContain(
      "Sent for signature",
    );
  });

  it("attaches the stamp only when the operator unticks the box", async () => {
    mockedList.mockResolvedValue([entry()]);
    mockedUpload.mockResolvedValue({
      agreementId: "ag-1",
      trackingReference: "AM7K3QPW9Z4",
      propertyCity: "Bengaluru",
      agreementStartDate: "2026-01-01",
      certificateNumberRedacted: "***234X",
      signingInitiated: false,
      signingNotStartedReason: null,
    });

    const wrapper = await filledForm();
    await wrapper.find('[data-testid="field-initiate-signing"]').setValue(false);

    mockedList.mockResolvedValue([]);
    await wrapper.find('[data-testid="queue-form-ag-1"]').trigger("submit");
    await flushPromises();

    expect(mockedUpload.mock.calls[0][2].initiateSigning).toBe(false);
    const result = wrapper.find('[data-testid="queue-result"]').text();
    expect(result).toContain("Stamped");
    expect(result).not.toContain("Sent for signature");
  });

  it("reports a stamp that landed and signing that did not, as two outcomes", async () => {
    // The certificate is spent either way. Saying only "stamped" would leave an operator believing
    // the parties had been invited when nobody has been.
    mockedList.mockResolvedValue([entry()]);
    mockedUpload.mockResolvedValue({
      agreementId: "ag-1",
      trackingReference: "AM7K3QPW9Z4",
      propertyCity: "Bengaluru",
      agreementStartDate: "2026-01-01",
      certificateNumberRedacted: "***234X",
      signingInitiated: false,
      signingNotStartedReason: "PROVIDER_UNAVAILABLE",
    });

    const wrapper = await filledForm();

    mockedList.mockResolvedValue([]);
    await wrapper.find('[data-testid="queue-form-ag-1"]').trigger("submit");
    await flushPromises();

    const result = wrapper.find('[data-testid="queue-result"]').text();
    expect(result).toContain("Stamped");
    expect(result).toContain("SIGNING DID NOT START");
    expect(result).toContain("PROVIDER_UNAVAILABLE");
  });

  /** Open a row's upload form with every mandatory field and a scan already filled in. */
  async function filledForm() {
    const wrapper = mount(StaffConsole);
    await flushPromises();
    await wrapper.find('[data-testid="queue-upload-ag-1"]').trigger("click");
    await wrapper
      .find('[data-testid="field-certificate-number"]')
      .setValue("IN-KA1234567890123");
    await wrapper
      .find('[data-testid="field-issue-date"]')
      .setValue("2026-01-15");
    await wrapper.find('[data-testid="field-duty-amount"]').setValue("500.00");
    await wrapper.find('[data-testid="field-jurisdiction"]').setValue("KA");
    const file = new File([new Uint8Array([1, 2, 3])], "certificate.png", {
      type: "image/png",
    });
    const input = wrapper.find('[data-testid="field-scan"]')
      .element as HTMLInputElement;
    Object.defineProperty(input, "files", { value: [file], configurable: true });
    await wrapper.find('[data-testid="field-scan"]').trigger("change");
    return wrapper;
  }

  it("will not submit until a scan and every mandatory field is present", async () => {
    mockedList.mockResolvedValue([entry()]);

    const wrapper = mount(StaffConsole);
    await flushPromises();
    await wrapper.find('[data-testid="queue-upload-ag-1"]').trigger("click");

    const submit = wrapper.find('[data-testid="queue-submit-ag-1"]');
    expect(submit.attributes("disabled")).toBeDefined();

    await wrapper
      .find('[data-testid="field-certificate-number"]')
      .setValue("IN-KA1234567890123");
    await wrapper
      .find('[data-testid="field-issue-date"]')
      .setValue("2026-01-15");
    await wrapper.find('[data-testid="field-duty-amount"]').setValue("500.00");
    await wrapper.find('[data-testid="field-jurisdiction"]').setValue("KA");
    // Still no scan -> still disabled.
    expect(
      wrapper.find('[data-testid="queue-submit-ag-1"]').attributes("disabled"),
    ).toBeDefined();
    expect(mockedUpload).not.toHaveBeenCalled();
  });

  it("pre-fills the certificate fields from the row so only the real number is typed", async () => {
    mockedList.mockResolvedValue([entry()]);

    const wrapper = mount(StaffConsole);
    await flushPromises();
    await wrapper.find('[data-testid="queue-upload-ag-1"]').trigger("click");

    const value = (testid: string) =>
      (wrapper.find(`[data-testid="${testid}"]`).element as HTMLInputElement)
        .value;

    // The reference seeds the number: never an empty field, and always an accepted shape. It is a
    // starting point -- the operator overwrites it with the number on the certificate they bought.
    expect(value("field-certificate-number")).toBe("AM7K3QPW9Z4");
    // Jurisdiction comes from the PINNED TEMPLATE's state, which is what decides the stamp paper.
    expect(value("field-jurisdiction")).toBe("KA");
    expect(value("field-duty-amount")).toBe("100");
    // Today, from the local calendar -- not toISOString(), which is yesterday in IST before 05:30.
    const now = new Date();
    const expected = `${now.getFullYear()}-${`${now.getMonth() + 1}`.padStart(
      2,
      "0",
    )}-${`${now.getDate()}`.padStart(2, "0")}`;
    expect(value("field-issue-date")).toBe(expected);
  });

  it("leaves jurisdiction empty rather than guessing when the template is unresolvable", async () => {
    mockedList.mockResolvedValue([
      entry({ templateName: null, templateState: null }),
    ]);

    const wrapper = mount(StaffConsole);
    await flushPromises();
    await wrapper.find('[data-testid="queue-upload-ag-1"]').trigger("click");

    expect(
      (
        wrapper.find('[data-testid="field-jurisdiction"]')
          .element as HTMLInputElement
      ).value,
    ).toBe("");
  });

  it("refuses a certificate number the server would reject, at the field", async () => {
    mockedList.mockResolvedValue([entry()]);

    const wrapper = mount(StaffConsole);
    await flushPromises();
    await wrapper.find('[data-testid="queue-upload-ag-1"]').trigger("click");
    // An underscore is outside the accepted character set; catching it here is what stops the
    // rejection arriving as a generic 400 that reads like a bad scan.
    await wrapper
      .find('[data-testid="field-certificate-number"]')
      .setValue("AM7K3QPW9Z4_STAMP");

    const message = wrapper.find(
      '[data-testid="field-certificate-number-error"]',
    );
    expect(message.exists()).toBe(true);
    expect(message.text()).toContain("underscores");

    const file = new File([new Uint8Array([1])], "c.png", {
      type: "image/png",
    });
    const input = wrapper.find('[data-testid="field-scan"]')
      .element as HTMLInputElement;
    Object.defineProperty(input, "files", {
      value: [file],
      configurable: true,
    });
    await wrapper.find('[data-testid="field-scan"]').trigger("change");

    // Everything else is pre-filled, so the number alone holds submission closed.
    expect(
      wrapper.find('[data-testid="queue-submit-ag-1"]').attributes("disabled"),
    ).toBeDefined();
    expect(mockedUpload).not.toHaveBeenCalled();
  });

  it("shows a role-appropriate message when the backend refuses the queue", async () => {
    mockedList.mockRejectedValue(new staffQueue.StaffQueueHttpError(403));

    const wrapper = mount(StaffConsole);
    await flushPromises();

    expect(wrapper.find('[data-testid="queue-error"]').text()).toContain(
      "staff",
    );
    expect(wrapper.find('[data-testid="queue-list"]').exists()).toBe(false);
  });

  it("explains a refused upload without leaking server detail", async () => {
    mockedList.mockResolvedValue([entry()]);
    mockedUpload.mockRejectedValue(new staffQueue.StaffQueueHttpError(409));

    const wrapper = mount(StaffConsole);
    await flushPromises();
    await wrapper.find('[data-testid="queue-upload-ag-1"]').trigger("click");
    await wrapper
      .find('[data-testid="field-certificate-number"]')
      .setValue("IN-KA1234567890123");
    await wrapper
      .find('[data-testid="field-issue-date"]')
      .setValue("2026-01-15");
    await wrapper.find('[data-testid="field-duty-amount"]').setValue("500.00");
    await wrapper.find('[data-testid="field-jurisdiction"]').setValue("KA");
    const file = new File([new Uint8Array([1])], "c.png", {
      type: "image/png",
    });
    const input = wrapper.find('[data-testid="field-scan"]')
      .element as HTMLInputElement;
    Object.defineProperty(input, "files", {
      value: [file],
      configurable: true,
    });
    await wrapper.find('[data-testid="field-scan"]').trigger("change");

    await wrapper.find('[data-testid="queue-form-ag-1"]').trigger("submit");
    await flushPromises();

    expect(wrapper.find('[data-testid="queue-submit-error"]').text()).toContain(
      "already",
    );
  });
});

// The payment gate now ships REQUIRED, so staff see orders on this queue that they cannot stamp.
// Unpaid orders are deliberately NOT filtered out - hiding them would turn "waiting on payment"
// into "vanished". The console therefore has to make the block legible, or an operator clicks a
// button, gets a 409, and goes looking for a certificate problem that does not exist.
describe("StaffConsole and the payment gate", () => {
  async function mountWith(entries: StampQueueEntry[]) {
    mockedList.mockResolvedValue(entries);
    const wrapper = mount(StaffConsole);
    await flushPromises();
    return wrapper;
  }

  it("marks an unpaid order as awaiting payment and will not let staff start an upload", async () => {
    const wrapper = await mountWith([entry({ paymentState: "UNPAID" })]);

    expect(wrapper.find('[data-testid="queue-payment-ag-1"]').text()).toContain(
      "Awaiting payment",
    );
    const upload = wrapper.find('[data-testid="queue-upload-ag-1"]');
    expect(upload.attributes("disabled")).toBeDefined();
    // The reason is on the control itself, not only in a badge elsewhere on the row.
    expect(upload.attributes("title")).toContain("paid");

    await upload.trigger("click");
    expect(wrapper.find('[data-testid="queue-form-ag-1"]').exists()).toBe(
      false,
    );
  });

  it("still shows the unpaid order rather than hiding it", async () => {
    // The whole point of not filtering: staff must be able to see the work exists.
    const wrapper = await mountWith([entry({ paymentState: "UNPAID" })]);

    expect(wrapper.find('[data-testid="queue-empty"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="queue-reference-ag-1"]').text()).toBe(
      "AM7K3QPW9Z4",
    );
  });

  it("leaves a paid order fully actionable", async () => {
    const wrapper = await mountWith([entry({ paymentState: "PAID" })]);

    expect(wrapper.find('[data-testid="queue-payment-ag-1"]').text()).toContain(
      "Paid",
    );
    expect(
      wrapper.find('[data-testid="queue-upload-ag-1"]').attributes("disabled"),
    ).toBeUndefined();
  });

  it("treats a staff waiver as good as paid - it is the operational escape hatch", async () => {
    const wrapper = await mountWith([entry({ paymentState: "WAIVED" })]);

    expect(wrapper.find('[data-testid="queue-payment-ag-1"]').text()).toContain(
      "waived",
    );
    expect(
      wrapper.find('[data-testid="queue-upload-ag-1"]').attributes("disabled"),
    ).toBeUndefined();
  });

  it("explains a payment-required refusal as payment, not as a certificate problem", async () => {
    // 409 is no longer one situation. Naming the wrong one sends an operator to re-check a
    // certificate that is perfectly fine.
    mockedUpload.mockRejectedValue(
      new staffQueue.StaffQueueHttpError(
        409,
        "urn:agreementmitra:problem:payment-required",
      ),
    );
    // The row reports PAID, so the UI lets the upload start; the server refuses anyway. That is the
    // case that matters - the client guard is presentational and the backend is the authority.
    const wrapper = await mountWith([entry({ paymentState: "PAID" })]);

    await wrapper.find('[data-testid="queue-upload-ag-1"]').trigger("click");
    await wrapper
      .find('[data-testid="field-certificate-number"]')
      .setValue("IN-KA1234567890123");
    await wrapper
      .find('[data-testid="field-issue-date"]')
      .setValue("2026-01-15");
    await wrapper.find('[data-testid="field-duty-amount"]').setValue("500.00");
    await wrapper.find('[data-testid="field-jurisdiction"]').setValue("KA");
    const file = new File([new Uint8Array([1])], "c.png", {
      type: "image/png",
    });
    const input = wrapper.find('[data-testid="field-scan"]')
      .element as HTMLInputElement;
    Object.defineProperty(input, "files", {
      value: [file],
      configurable: true,
    });
    await wrapper.find('[data-testid="field-scan"]').trigger("change");

    await wrapper.find('[data-testid="queue-form-ag-1"]').trigger("submit");
    await flushPromises();

    const message = wrapper.find('[data-testid="queue-submit-error"]').text();
    expect(message).toContain("not been paid");
    expect(message).not.toContain("certificate has already been used");
  });

  // --- purchasing context (staff-queue-fulfilment-context) ------------------------------------
  //
  // The operator buys the e-stamp off this row. If the state or a party name is missing from it,
  // the vendor's form cannot be filled without going back to the database - which is the thing the
  // console exists to prevent.

  it("shows the template and its state, because the state selects the stamp to buy", async () => {
    mockedList.mockResolvedValue([entry()]);

    const wrapper = mount(StaffConsole);
    await flushPromises();

    expect(wrapper.find('[data-testid="queue-state-ag-1"]').text()).toBe("KA");
    expect(wrapper.find('[data-testid="queue-template-ag-1"]').text()).toBe(
      "Karnataka Residential Rental Agreement",
    );
  });

  it("lists every party with their father's name, grouped by side", async () => {
    mockedList.mockResolvedValue([
      entry({
        parties: [
          { role: "OWNER", name: "Asha Rao", fatherName: "Krishna Rao" },
          { role: "OWNER", name: "Meera Rao", fatherName: "Krishna Rao" },
          { role: "TENANT", name: "Bilal Khan", fatherName: "Imran Khan" },
        ],
      }),
    ]);

    const wrapper = mount(StaffConsole);
    await flushPromises();

    const parties = wrapper.find('[data-testid="queue-parties-ag-1"]').text();
    expect(parties).toContain("First party");
    expect(parties).toContain("Second party");
    // Both owners, not just the first: a missing name is a certificate that cannot be bought.
    expect(parties).toContain("Asha Rao");
    expect(parties).toContain("Meera Rao");
    expect(parties).toContain("Bilal Khan");
    // Neutral label -- we store a father's name but no gender, so "S/o" would misdescribe some.
    expect(parties).toContain("Father: Krishna Rao");
    expect(parties).toContain("Father: Imran Khan");
    expect(parties).not.toContain("S/o");
  });

  it("says the template is unavailable rather than leaving a gap, and keeps the row usable", async () => {
    mockedList.mockResolvedValue([
      entry({ templateName: null, templateState: null }),
    ]);

    const wrapper = mount(StaffConsole);
    await flushPromises();

    expect(
      wrapper.find('[data-testid="queue-template-missing-ag-1"]').text(),
    ).toContain("Template unavailable");
    expect(wrapper.find('[data-testid="queue-state-ag-1"]').exists()).toBe(
      false,
    );
    // The work is still outstanding, so the row must still be actionable.
    expect(wrapper.find('[data-testid="queue-upload-ag-1"]').exists()).toBe(
      true,
    );
    expect(wrapper.find('[data-testid="queue-parties-ag-1"]').text()).toContain(
      "Asha Rao",
    );
  });
});
