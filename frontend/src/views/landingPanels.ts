// The landing page's "Before you decide" tab panels. Ids only -- the tab labels are copy and stay in
// LandingPage.vue. panelForHash is the allowlist: only a PanelId it returns may reach the DOM or the
// URL, so hash or href text never flows into a selector or a navigation.
export const PANEL_IDS = ["guarantees", "status", "faq"] as const;

export type PanelId = (typeof PANEL_IDS)[number];

export function panelForHash(hash: string): PanelId | null {
  return PANEL_IDS.find((id) => hash === `#${id}`) ?? null;
}
