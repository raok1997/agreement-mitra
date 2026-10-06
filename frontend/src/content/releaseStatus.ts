// The home page's ONLY statement of what is live. Nothing else on the page asserts or denies
// availability (LandingPage.test.ts enforces that), so flipping a row at launch is one `state:` edit.
//
// LAUNCH CHECKLIST -- before flipping a stamping or eSign row to "live", or making a state's rules
// chargeable:
//   - the production rail is non-stub and has been observed live once (docs/ROADMAP.md "Ops / config");
//   - ops has a confirmed channel for that state's stamps, and ToS §8/§14 and the privacy policy's
//     recipient roles name it (the server's eligibility gate checks duty rules, not channels);
//   - #price and FAQ 3 on the home page still match ToS §7 and that state's `offer` policy in
//     backend/src/main/resources/rules/stamp-paper/<ST>.yaml.
// Counsel review is enforced automatically: releaseStatus.test.ts refuses a "live" stamping row
// while any of that state's stamp-duty rule files has `counselReview: null`.
//
// ToS §2 and §5 defer to this board and to the server's eligibility decision, so they state no
// availability of their own. Adding a STATE (drafting scope) touches three places: ToS §5, the home
// page FAQ "Which cities do you serve?", and the TemplatePicker filter. Also re-read at a flip: the
// jurisdiction-eligibility in-app disclosure and the #how step titles.

export type ReleaseState = "live" | "soon" | "planned";

export const RELEASE_STATE_LABEL: Record<ReleaseState, string> = {
  live: "Live now",
  soon: "In integration",
  planned: "Planned",
};

export interface ReleaseRow {
  label: string;
  state: ReleaseState;
  stampingState?: "TG" | "KA";
}

export const RELEASE_STATUS: readonly ReleaseRow[] = [
  { label: "Guided agreement builder (Telangana, Karnataka)", state: "live" },
  { label: "Live document preview", state: "live" },
  { label: "Download a draft PDF", state: "live" },
  { label: "Save and resume with Google", state: "live" },
  { label: "Stamping: Telangana", state: "soon", stampingState: "TG" },
  { label: "Stamping: Karnataka", state: "soon", stampingState: "KA" },
  { label: "Aadhaar OTP eSign", state: "soon" },
  { label: "Telugu and Hindi agreements", state: "planned" },
];
