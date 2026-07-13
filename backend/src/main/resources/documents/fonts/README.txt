Noto font faces for the HTML live-preview pane
==============================================

The live HTML preview (DocumentRenderer.renderHtml) embeds the Noto faces as
@font-face data-URIs so a browser shapes Latin + Devanagari/Telugu (complex
Indic scripts) the same way the Gotenberg PDF path does. Gotenberg resolves its
faces from the image (fonts-noto-core); a browser has no access to those, so the
HTML pane must carry the fonts itself.

Drop the TTF binaries here (they are deploy artifacts, not committed to source,
mirroring how docker/gotenberg installs fonts-noto-core rather than committing
font blobs):

  documents/fonts/NotoSans.ttf              (Noto Sans, Latin)
  documents/fonts/NotoSansDevanagari.ttf    (Noto Sans Devanagari, Indic)

Fonts: Noto Sans + Noto Sans Devanagari, SIL Open Font License 1.1 (no cost).
Source: https://fonts.google.com/noto

HtmlFontEmbedder loads whatever faces are present at these classpath paths and
embeds them; a missing face is skipped (the render never fails for a missing
font -- it degrades to system fonts for that script, the pre-existing behaviour).
When these binaries are added, the HTML pane becomes font-self-contained with no
further code change.
