// The legal entity that operates AgreementMitra (operating-entity-disclosure D1, D4).
//
// THIS MODULE HAS NO IMPORTS AND NO import.meta.env, so vite.config.ts can load it in Node: the
// config-time gate below and (seo-foundations 2.6) the index.html JSON-LD both read from here.
//
// The legal name is COMMITTED, never env: the terms of service name the same party in plain text.
// It restates the constant OperatingEntity.LEGAL_NAME in the backend; operatorFacts.test.ts holds
// them equal. The LLPIN and registered office are pending and come from VITE_OPERATOR_* at build time;
// blank means "not yet issued". There is no GSTIN here: it belongs on invoices, which are backend
// output.

export const OPERATOR_LEGAL_NAME = "KAVISAT TEK LABS LLP";

/** Kept equal to `LLPIN_REGEX` in backend OperatingEntity.java by operatorFacts.test.ts. */
export const LLPIN_PATTERN = /^[A-Z]{3}-\d{4}$/;

/**
 * ASCII letters, digits, U+0020 and `, . - / # ( ) & '` only, at most 200 characters. It reaches
 * HTML and, later, a JSON-LD <script>, so anything that could break out of either is refused.
 */
export const REGISTERED_OFFICE_PATTERN = /^[A-Za-z0-9 ,./#()&'-]{1,200}$/;

export type OperatorEnv = Partial<
  Record<"VITE_OPERATOR_LLPIN" | "VITE_OPERATOR_REGISTERED_OFFICE", string>
>;

/** Throws, naming the variable but not its value, when a non-blank value fails its rule. */
export function assertOperatorEnv(env: OperatorEnv): void {
  const llpin = (env.VITE_OPERATOR_LLPIN ?? "").trim();
  if (llpin !== "" && !LLPIN_PATTERN.test(llpin)) {
    throw new Error(
      "VITE_OPERATOR_LLPIN is not a valid LLPIN (AAA-0000); leave it blank until issued",
    );
  }
  const office = (env.VITE_OPERATOR_REGISTERED_OFFICE ?? "").trim();
  if (office !== "" && !REGISTERED_OFFICE_PATTERN.test(office)) {
    throw new Error(
      "VITE_OPERATOR_REGISTERED_OFFICE may hold only ASCII letters, digits, spaces and , . - / # ( ) & ' (at most 200)",
    );
  }
}
