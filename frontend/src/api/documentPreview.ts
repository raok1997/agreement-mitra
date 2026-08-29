// Stateless document-projection preview client (document-capture-shell-wiring). Kept in its own
// module so the existing client.ts (create/generate/sign) and templateForm.ts / templateCatalog.ts
// stay untouched. It calls the documents module's projection endpoint:
//
//   POST /api/templates/document/preview   body: { dimensions?, data }
//
// `data` is a FLAT field-key -> value map (the working set flattened across sections), keyed by the
// FormSchema field `key` -- NOT a nested signers[] object (that was the retired /api/agreements/preview
// shape). `dimensions` carries the SAME (state, type) the FormSchema was fetched for, so the previewed
// document resolves the same effective template the form was projected from (see flow-journal 8.2/8.3).
//
// The endpoint persists nothing server-side and is served `no-store`; the HTML variant is meant to be
// dropped into a sandboxed iframe via `srcdoc`. The working set is full party PII: nothing here logs
// the data map or the rendered document.

import type { FormDimensions } from "./templateForm";

/** The flattened working-set data map POSTed to the stateless preview: field key -> string value. */
export type PreviewData = Record<string, string>;

const BASE = "/api";
const PREVIEW = `${BASE}/templates/document/preview`;

/**
 * The request body: an optional (state, type), the field-key data map, and the set of added optional
 * section titles. Mirrors DocumentProjectionRequest { dimensions?, data, activeSections }.
 */
interface DocumentProjectionRequestBody {
  dimensions?: FormDimensions;
  data: PreviewData;
  /**
   * Titles of the added optional sections to render. The compiler renders an optional section iff its
   * title is present here (mandatory sections always render; unknown titles are ignored -- M2). Sent as
   * `[]` when none are active, so the two preview faces (HTML + PDF) request identical content. Carries
   * only system-owned section titles (template metadata), never user data.
   */
  activeSections: string[];
  /**
   * The provenance-line reference shown at the document foot: after save, the agreement's tracking
   * number (so the post-save preview matches the saved document); omitted before save (the server
   * then shows its PREVIEW marker). Non-PII display text, HTML-escaped server-side.
   */
  documentReference?: string;
}

/**
 * POST the working-set data map to the stateless projection endpoint with the given `Accept`
 * (`text/html` for the live pane, `application/pdf` for Download PDF). `dimensions` is optional; when
 * omitted the server resolves the reference default (state, type). `activeSections` is the set of added
 * optional section titles (`[]` = mandatory-only render). Rejects on a non-2xx -- the message carries
 * only the status code, never the submitted data, dimensions, or section titles.
 */
async function postDocumentPreview(
  data: PreviewData,
  accept: string,
  dimensions?: FormDimensions,
  activeSections: string[] = [],
  documentReference?: string,
): Promise<Response> {
  const body: DocumentProjectionRequestBody = { data, activeSections };
  if (dimensions) body.dimensions = dimensions;
  if (documentReference) body.documentReference = documentReference;
  const res = await fetch(PREVIEW, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: accept },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`Preview failed (${res.status}).`);
  return res;
}

/**
 * Render the working set to escaped HTML for the live preview pane. The HTML is self-contained (fonts
 * embedded) and meant to be dropped into a sandboxed iframe via `srcdoc`. Never cached (server sends
 * `no-store`); we never persist or log it.
 */
export async function fetchDocumentPreviewHtml(
  data: PreviewData,
  dimensions?: FormDimensions,
  activeSections: string[] = [],
  documentReference?: string,
): Promise<string> {
  const res = await postDocumentPreview(
    data,
    "text/html",
    dimensions,
    activeSections,
    documentReference,
  );
  return res.text();
}

/**
 * Render the working set to a PDF blob (the `application/pdf` variant) for Download PDF. The caller
 * owns any object URL it creates from the blob and MUST revoke it.
 */
export async function fetchDocumentPreviewPdf(
  data: PreviewData,
  dimensions?: FormDimensions,
  activeSections: string[] = [],
  documentReference?: string,
): Promise<Blob> {
  const res = await postDocumentPreview(
    data,
    "application/pdf",
    dimensions,
    activeSections,
    documentReference,
  );
  return res.blob();
}
