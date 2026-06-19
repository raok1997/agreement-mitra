// Thin API client. Keep all backend calls here, not scattered in components.

export interface SignSession {
  providerRequestId: string;
  signingUrl: string;
}

const BASE = "/api";

export async function requestSignature(agreementId: string): Promise<SignSession> {
  const res = await fetch(`${BASE}/signing/${agreementId}/request`, { method: "POST" });
  if (!res.ok) throw new Error(`Sign request failed: ${res.status}`);
  return res.json();
}
