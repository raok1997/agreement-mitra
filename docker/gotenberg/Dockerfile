# Thin Gotenberg image that bundles the Noto faces so its headless Chromium can shape
# Latin + Devanagari offline. Gotenberg (MIT), the Chromium it wraps (BSD), and the Noto
# fonts (SIL OFL) are all open source -- no licensing cost. Pulled/built once like Postgres
# or MinIO; nothing is downloaded at app runtime and no browser binary ships in the app.
FROM gotenberg/gotenberg:8

# apt + fc-cache need root; drop back to the unprivileged gotenberg user afterwards.
USER root

# fonts-noto-core carries Noto Sans (Latin) and Noto Sans Devanagari (the Indic coverage the
# rental-agreement template needs). Refresh the fontconfig cache so Chromium resolves them by
# family name -- no @font-face URL fetch, so the render stays fully offline.
RUN apt-get update \
 && apt-get install -y --no-install-recommends fonts-noto-core \
 && rm -rf /var/lib/apt/lists/* \
 && fc-cache -f

USER gotenberg
