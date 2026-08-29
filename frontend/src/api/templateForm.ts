// Form-schema API client (template-form-projection). Keep all backend calls in src/api/ per
// conventions -- kept in its own module so the existing client.ts / templateCatalog.ts / preview.ts
// stay untouched. The FormSchema is system-owned template metadata (field keys, labels, widgets,
// types, validation bounds, enum options) and carries NO user data, so it is safe to fetch and cache.
//
// These types mirror the backend documents.api DTO tree exactly (FormSchema / FormSection /
// FormField / FormField.Validation) served by GET /api/templates/form?state=..&type=..

/** The resolved widget-vocabulary token the client renders (from the field's FieldType). */
export type Widget =
  | "text"
  | "textarea"
  | "number"
  | "money"
  | "date"
  | "checkbox"
  | "select";

/** The raw field-type token so the client can parse/format. */
export type FieldType =
  | "text"
  | "longtext"
  | "int"
  | "money"
  | "date"
  | "bool"
  | "enum";

/**
 * Declarative validation bounds for client-side checks, projected verbatim from the definition's
 * field validation. Client validation is a UX affordance, NOT the trust boundary -- authoritative
 * validation of submitted data is server-side (document projection). All parts are optional.
 */
export interface FormFieldValidation {
  min?: number;
  max?: number;
  minLength?: number;
  maxLength?: number;
  pattern?: string;
}

/** One enum choice: the stored `value` the client submits and the human `label` it displays. */
export interface FormFieldOption {
  value: string;
  label: string;
}

/** One form input in a section, projected from a definition field. Carries no user data. */
export interface FormField {
  key: string;
  label: string;
  widget: Widget;
  type: FieldType;
  required: boolean;
  /** Type-typed literal (boolean / number / string) when declared; JSON key is "default". */
  default?: boolean | number | string;
  /** Present only for enum fields -- each allowed value with its human display label. */
  options?: FormFieldOption[];
  /** Optional grouping hint. */
  group?: string;
  validation?: FormFieldValidation;
  /** Opaque conditional-visibility expression, carried verbatim and NOT evaluated in this CR. */
  showWhen?: string;
}

/** One ordered group of form inputs. Clause-id entries of the source section are not present here. */
export interface FormSection {
  title: string;
  fields: FormField[];
  /** false = a Mandatory capture section, true = an Optional one (for the add-optional catalog). */
  optional: boolean;
  /** Declared document render token (parties | keyvalue | clauses | annexure), opaque to the UI. */
  renderKind: string;
}

/** The (state, type) pair a schema was projected for. */
export interface FormDimensions {
  state: string;
  type: string;
}

/**
 * A data-independent projection of one resolved effective template into the sections and fields a
 * capture form renders. contentHash is the cache key / HTTP ETag.
 */
export interface FormSchema {
  dimensions: FormDimensions;
  templateId: string;
  version: number;
  contentHash: string;
  sections: FormSection[];
}

const BASE = "/api";

/**
 * Fetch the projected FormSchema for a (state, type) pair. Rejects on a non-2xx -- notably a 404 for
 * an unknown/unresolvable pair. The thrown message carries only the status code, never the requested
 * state/type, so a failure cannot leak which dimensions were probed (mirroring the server's
 * never-echo error contract).
 */
export async function getTemplateForm(
  state: string,
  type: string,
): Promise<FormSchema> {
  const params = new URLSearchParams({ state, type });
  const res = await fetch(`${BASE}/templates/form?${params.toString()}`);
  if (!res.ok) throw new Error(`Failed to load the form (${res.status}).`);
  return res.json();
}
