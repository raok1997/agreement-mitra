-- V5 — draft-ingestion (CR-5): the uploaded rental-agreement draft PDF.
--
-- Adds the object-storage key of the user's uploaded draft to the agreement aggregate.
-- Nullable: an agreement has no draft until one is uploaded. The PDF *bytes* live in object
-- storage (MinIO/S3) — only the key is persisted here, never the bytes. String maps to text
-- (the V2 convention) so Hibernate `ddl-auto: validate` passes. Forward-only: never edits an
-- applied migration.

ALTER TABLE agreement ADD COLUMN draft_pdf_key TEXT;
