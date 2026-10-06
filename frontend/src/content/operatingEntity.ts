// The resolved operator for this build (operating-entity-disclosure D4). Values are baked in at
// build time, so rendering them makes no request.
import { OPERATOR_LEGAL_NAME, type OperatorEnv } from "./operatorFacts";

export interface OperatingEntity {
  legalName: string;
  /** null until issued. */
  llpin: string | null;
  /** null until confirmed. */
  registeredOffice: string | null;
}

function present(value: string | undefined): string | null {
  const trimmed = (value ?? "").trim();
  return trimmed === "" ? null : trimmed;
}

export function resolveOperatingEntity(env: OperatorEnv): OperatingEntity {
  return {
    legalName: OPERATOR_LEGAL_NAME,
    llpin: present(env.VITE_OPERATOR_LLPIN),
    registeredOffice: present(env.VITE_OPERATOR_REGISTERED_OFFICE),
  };
}

// Each variable is named explicitly. NEVER pass import.meta.env whole: Vite inlines a whole-object
// reference as every VITE_* value it can see, which would put any future key, or a stray secret in
// a build env, into the public bundle.
const llpin = import.meta.env.VITE_OPERATOR_LLPIN;
const office = import.meta.env.VITE_OPERATOR_REGISTERED_OFFICE;

export const OPERATING_ENTITY: OperatingEntity = resolveOperatingEntity({
  VITE_OPERATOR_LLPIN: llpin,
  VITE_OPERATOR_REGISTERED_OFFICE: office,
});

/** The committed defaults: what the generated counsel document renders, whatever the build env. */
export const OPERATING_ENTITY_DEFAULTS: OperatingEntity =
  resolveOperatingEntity({});
