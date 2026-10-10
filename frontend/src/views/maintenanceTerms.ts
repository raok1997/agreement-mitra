// The frontend's single copy of the rental template's maintenance facts
// (backend/src/main/resources/documents/template/sets/rental/base.yaml). The capture form's Fixed rule
// and the key-terms summary both read these; maintenanceTerms.test.ts fails if they drift from the
// template.

export const CHARGES_SECTION_TITLE = "Charges & Utilities";

export const MAINTENANCE_MODE_KEY = "maintenanceMode";
export const MAINTENANCE_AMOUNT_KEY = "maintenanceAmount";

/** The pre-v8 key (removed in rental base v8); an agreement pinned to v7 still stores it. */
export const LEGACY_MAINTENANCE_KEY = "maintenanceBorneBy";

export const MAINTENANCE_MODES = [
  "included_in_rent",
  "fixed_amount",
  "as_billed_by_society",
  "paid_by_owner",
] as const;

export type MaintenanceMode = (typeof MAINTENANCE_MODES)[number];

export const FIXED_AMOUNT = "fixed_amount" satisfies MaintenanceMode;

/** The template default, which the server fills and the deed states when no mode is stored. */
export const DEFAULT_MAINTENANCE_MODE: MaintenanceMode = "as_billed_by_society";

export function isMaintenanceMode(value: string): value is MaintenanceMode {
  return (MAINTENANCE_MODES as readonly string[]).includes(value);
}
