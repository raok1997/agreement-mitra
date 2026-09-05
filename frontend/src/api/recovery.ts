// Asking for an agreement's recovery link to be re-sent.
//
// The server answers 202 with an empty body in EVERY case - unknown reference, unpaid, already
// claimed, nobody contactable, throttled, or sent. There is deliberately nothing to parse and
// nothing to branch on, and this module must not invent a distinction the server refuses to make.

const BASE = "/api";

/**
 * Request the recovery link for a reference.
 *
 * Resolves when the server accepted the request. That is NOT a statement that an email was sent, or
 * that the reference matched anything - only that we asked. Rejects only on a transport or server
 * failure, which says nothing about the reference either.
 */
export async function requestRecovery(reference: string): Promise<void> {
  const res = await fetch(`${BASE}/agreements/recovery`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ reference }),
  });
  if (!res.ok) throw new Error(`Recovery request failed: ${res.status}`);
}
