// Template-catalog API client (contract-only). Keep all backend calls here, not scattered in
// components. This is the browse/select surface for the template picker screen; the picker view
// itself is intentionally NOT built here (its shell depends on the not-yet-applied
// preview-centric-capture work). These types mirror the backend `documents.api` DTOs
// (TemplateSummary / TemplateDetail) exposed by GET /api/templates and GET /api/templates/{id}.

/** A browse-list item: system-owned metadata only (no template body, no pointer). */
export interface TemplateSummary {
  id: string;
  name: string;
  description: string | null;
  type: string;
  state: string;
  /** Reserved; English only for now. */
  language: string;
  /** Monotonic template version; numeric to match the backend `int` (ordered numerically). */
  version: number;
}

/** The selection coordinates carried forward into capture. */
export interface TemplateDimensions {
  state: string;
  type: string;
  language: string;
}

/** One published catalog entry with its dimensions. */
export interface TemplateDetail {
  id: string;
  name: string;
  description: string | null;
  dimensions: TemplateDimensions;
  /** Monotonic template version; numeric to match the backend `int` (ordered numerically). */
  version: number;
}

const BASE = "/api";

/**
 * List published templates, optionally filtered by state, type, and a free-text query over
 * name/description. Only published entries are ever returned; draft/deprecated are hidden server-side.
 */
export async function listTemplates(
  filters: { state?: string; type?: string; q?: string } = {},
): Promise<TemplateSummary[]> {
  const params = new URLSearchParams();
  if (filters.state) params.set("state", filters.state);
  if (filters.type) params.set("type", filters.type);
  if (filters.q) params.set("q", filters.q);
  const query = params.toString();
  const res = await fetch(`${BASE}/templates${query ? `?${query}` : ""}`);
  if (!res.ok) throw new Error(`Failed to load templates (${res.status}).`);
  return res.json();
}

/**
 * Fetch one published template by id. Rejects on 404 (unknown or non-published id -- the server
 * gives the same response for both, so a draft/deprecated entry's existence cannot be probed).
 */
export async function getTemplate(id: string): Promise<TemplateDetail> {
  const res = await fetch(`${BASE}/templates/${encodeURIComponent(id)}`);
  if (!res.ok) throw new Error(`Failed to load template (${res.status}).`);
  return res.json();
}
