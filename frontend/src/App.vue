<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import CaptureForm from "./views/CaptureForm.vue";
import RecoverAgreement from "./views/RecoverAgreement.vue";
import MyAgreements from "./views/MyAgreements.vue";
import TemplatePicker from "./components/TemplatePicker.vue";
import AuthCallback from "./views/AuthCallback.vue";
import LandingPage from "./views/LandingPage.vue";
import TermsOfService from "./views/TermsOfService.vue";
import StaffConsole from "./views/StaffConsole.vue";
import wordmark from "./assets/logo-wordmark.svg";
import { auth, logout } from "./api/authStore";
import { googleStartUrl } from "./api/auth";
import { getAgreement } from "./api/agreements";
import type { AgreementView } from "./api/client";

// Lightweight view-switch (no vue-router -- see flow-journal 8.3): the picker is the entry step, and
// on selection we mount the capture shell with the chosen (state, type) as props. "Change template"
// clears the selection and returns to the picker. Keeping this router-less avoids widening the
// frontend dependency/OSV surface for a small flow.
const selection = ref<{ state: string; type: string } | null>(null);

// Top-level mode (agreement-ownership CR): "create" drafts a new agreement (picker -> capture);
// "list" shows the signed-in caller's "My Agreements"; "edit" reopens an owned agreement in the
// capture form. Drafting ("create") never requires a login; "list"/"edit" do (guarded below).
const mode = ref<"create" | "list" | "edit">("create");
const editTarget = ref<AgreementView | null>(null);
const editError = ref<string | null>(null);

// Top-level route, still resolved from the path by hand rather than via vue-router (see
// flow-journal 8.3 -- a router is more OSV surface than three paths are worth):
//   "/"              -> the public marketing page (LandingPage.vue), no app chrome
//   "/start"         -> the agreement app (picker -> capture, "My agreements")
//   "/staff"         -> the STAFF fulfilment console (orders awaiting an e-stamp)
//   "/terms"         -> the published terms of service, reachable without app chrome or a session
//                       (the in-product disclaimer links here, and it must open for a reader who
//                       has no account and is halfway through paying)
//   "/auth/callback" -> the OAuth landing the backend 302s to, which exchanges the
//                       handoff for a session and then drops the caller into the app
// Anything else falls through to the app so deep links do not dead-end on the marketing page.
type Route =
  | "landing"
  | "app"
  | "callback"
  | "staff"
  | "recover"
  | "openLink"
  | "terms";

/** `/agreement/<uuid>` - the link emailed to the parties after payment. */
const AGREEMENT_LINK =
  /^\/agreement\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i;

function routeFor(pathname: string): Route {
  if (pathname === "/auth/callback") return "callback";
  if (pathname === "/staff") return "staff";
  if (pathname === "/terms") return "terms";
  if (pathname === "/recover") return "recover";
  if (AGREEMENT_LINK.test(pathname)) return "openLink";
  if (pathname === "/" || pathname === "") return "landing";
  return "app";
}

/** The agreement id carried by a recovery link, or null when this is not one. */
function agreementIdFromPath(pathname: string): string | null {
  const match = AGREEMENT_LINK.exec(pathname);
  return match ? match[1] : null;
}

// Route guard for the staff console. Presentational only, and deliberately so: it stops a customer
// being shown a screen that would 403, but it authorizes nothing. The queue and the intake endpoint
// are both role-gated in the backend's filter chain, which is what actually protects them -- a user
// who edits this flag in memory still gets 403 from the server and sees no order.
const isStaff = computed(() => auth.me?.role === "STAFF");

const route = ref<Route>(routeFor(window.location.pathname));

// Back/forward between the landing page and the app are real history entries, so keep the
// rendered route in step with them.
function syncRoute(): void {
  route.value = routeFor(window.location.pathname);
}
onMounted(() => {
  window.addEventListener("popstate", syncRoute);
  // A recovery link resolves on arrival: the customer clicked a link to their agreement, so it
  // should open, not present another step.
  if (route.value === "openLink") void openFromLink();
});
onUnmounted(() => window.removeEventListener("popstate", syncRoute));

function navigate(path: string, mode: "push" | "replace" = "push"): void {
  if (mode === "push") window.history.pushState({}, "", path);
  else window.history.replaceState({}, "", path);
  syncRoute();
}

// The landing page's CTAs all funnel here: enter the app at the template picker.
function enterApp(): void {
  goToCreate();
  navigate("/start");
}

function onSelect(dimensions: { state: string; type: string }): void {
  selection.value = dimensions;
}
function onChangeTemplate(): void {
  selection.value = null;
}
function leaveCallback(): void {
  // Replace, not push: the callback URL carries a one-shot handoff and must not sit in
  // history. Land in the app rather than on the marketing page -- login is only ever
  // started from inside the app.
  navigate("/start", "replace");
}
// The terms are reached from a link on whatever screen the reader was on, so "Back" means back --
// the browser's own history, which is the only thing that knows where they came from. A directly
// opened /terms has nowhere to return to, so it falls through to the marketing page.
function leaveTerms(): void {
  if (window.history.length > 1) window.history.back();
  else navigate("/", "replace");
}
function signIn(): void {
  // Full navigation: the backend redirects to Google, then back to /auth/callback.
  window.location.href = googleStartUrl();
}
function signOut(): void {
  void logout();
  goToCreate();
}

// Guard (D8): the authenticated views require a session; without one, send the user to login.
function showMyAgreements(): void {
  if (!auth.session) {
    signIn();
    return;
  }
  editTarget.value = null;
  editError.value = null;
  mode.value = "list";
}

function goToCreate(): void {
  mode.value = "create";
  selection.value = null;
  editTarget.value = null;
}

// --- recovery link landing ---------------------------------------------------------------------
//
// A party opened the link we emailed after payment. The link carries the agreement id, which is the
// same bearer capability the customer held in their browser before they closed the tab - so there is
// nothing to redeem here and no second credential to exchange. Loading the agreement by id through
// the endpoints that already serve unowned agreements IS the recovery.
//
// It stops working once the agreement is claimed into an account: the server then refuses anonymous
// callers, which is the revocation the emailed message promised. That case must read as "this is
// saved to an account, sign in" rather than "not found", or a party whose counterparty saved the
// agreement is left staring at a dead end with no idea why.

const linkError = ref<string | null>(null);
const linkNeedsSignIn = ref(false);

/** Leave the recovery form for the marketing page. */
function leaveRecover(): void {
  navigate("/");
}

async function openFromLink(): Promise<void> {
  const id = agreementIdFromPath(window.location.pathname);
  if (!id) return;
  linkError.value = null;
  linkNeedsSignIn.value = false;
  try {
    editTarget.value = await getAgreement(id);
    mode.value = "edit";
    route.value = "app";
    // Drop the id out of the address bar once it has been used. It stays in history either way -
    // this is tidiness, not a security control; the referrer policy in index.html is what stops it
    // leaking to another origin.
    window.history.replaceState({}, "", "/app");
  } catch {
    // A claimed agreement and an unknown one look identical from here by design (the server returns
    // the same 404 so ownership cannot be probed), so the message has to cover both without
    // asserting either.
    linkNeedsSignIn.value = !!auth.session === false;
    linkError.value =
      "This link no longer opens the agreement. If it has been saved to an account, sign in to open it.";
  }
}

// From the list: load the chosen agreement and reopen it in the capture form (edit mode).
async function openForEdit(id: string): Promise<void> {
  if (!auth.session) {
    signIn();
    return;
  }
  editError.value = null;
  try {
    editTarget.value = await getAgreement(id);
    mode.value = "edit";
  } catch {
    editError.value = "Could not open that agreement.";
    mode.value = "list";
  }
}

function onSavedToAccount(): void {
  // A fresh agreement was just claimed, or an edit saved -- return to the list to see it.
  showMyAgreements();
}
</script>

<template>
  <!-- "I lost the link." Reachable without an account, because the customer it serves has none. -->
  <RecoverAgreement v-if="route === 'recover'" @back="leaveRecover" />
  <!-- A recovery link is resolving, or could not be. -->
  <section v-else-if="route === 'openLink'" class="mx-auto max-w-lg p-6">
    <template v-if="linkError">
      <h2 class="text-lg font-semibold text-slate-900">This link did not open</h2>
      <p class="mt-2 text-sm text-slate-600">{{ linkError }}</p>
      <button
        v-if="linkNeedsSignIn"
        type="button"
        class="mt-4 rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white"
        @click="signIn"
      >
        Sign in
      </button>
    </template>
    <p v-else class="text-sm text-slate-600">Opening your agreement...</p>
  </section>
  <!-- The terms of service. Like the landing page it is public and chrome-free: it is linked from
       the in-product disclaimer, and a reader following that link is not necessarily signed in. -->
  <TermsOfService
    v-else-if="route === 'terms'"
    @back="leaveTerms"
  />
  <!-- "/" is the public marketing page: full-bleed, no app chrome, no API calls. -->
  <LandingPage v-else-if="route === 'landing'" @start="enterApp" />

  <div v-else class="min-h-screen bg-ink-50 text-ink-800">
    <!-- print:hidden - the nav is chrome. It is here so that printing the payment confirmation
         (its "Print or save as PDF" button) yields a receipt, not a screenshot of the app. -->
    <header class="border-b border-ink-200 bg-white print:hidden">
      <div
        class="mx-auto flex max-w-3xl items-center justify-between gap-4 px-4 py-2.5"
      >
        <button type="button" class="flex items-center" @click="goToCreate">
          <img :src="wordmark" alt="AgreementMitra" class="h-8" />
        </button>
        <div class="flex items-center gap-3">
          <span class="hidden text-sm text-ink-500 sm:inline"
            >Rental agreements made simple</span
          >
          <span v-if="auth.session" class="flex items-center gap-2 text-sm">
            <button
              type="button"
              class="rounded border border-ink-200 px-2 py-1 text-ink-700 hover:bg-ink-50"
              data-testid="nav-my-agreements"
              @click="showMyAgreements"
            >
              My agreements
            </button>
            <button
              v-if="isStaff"
              type="button"
              class="rounded border border-ink-200 px-2 py-1 text-ink-700 hover:bg-ink-50"
              data-testid="nav-staff-console"
              @click="navigate('/staff')"
            >
              Stamp queue
            </button>
            <span class="text-ink-600">{{
              auth.me?.displayName || auth.me?.email
            }}</span>
            <button
              type="button"
              class="rounded border border-ink-200 px-2 py-1 text-ink-600 hover:bg-ink-50"
              @click="signOut"
            >
              Sign out
            </button>
          </span>
          <!-- For the customer who paid, closed the tab, and has no account to sign in to. Sits
               beside sign-in because that is where someone looks when they want to get back to
               something. -->
          <button
            v-if="!auth.session"
            type="button"
            class="text-sm text-ink-700 underline"
            @click="navigate('/recover')"
          >
            Find my agreement
          </button>
          <button
            v-if="!auth.session"
            type="button"
            class="rounded border border-ink-200 px-2 py-1 text-sm text-ink-700 hover:bg-ink-50"
            @click="signIn"
          >
            Sign in with Google
          </button>
        </div>
      </div>
    </header>

    <AuthCallback v-if="route === 'callback'" @done="leaveCallback" />

    <!-- Staff fulfilment console. Guarded twice over: hidden without the STAFF role here, and
         refused by the backend regardless of what this client believes. -->
    <main
      v-else-if="route === 'staff'"
      class="mx-auto flex max-w-5xl flex-col gap-3 px-4 py-4"
    >
      <StaffConsole v-if="isStaff" />
      <p v-else class="py-8 text-sm text-ink-600" data-testid="staff-forbidden">
        This console is for AgreementMitra staff.
      </p>
    </main>

    <main v-else class="mx-auto flex max-w-7xl flex-col gap-1.5 px-4 pb-3 pt-2">
      <!-- My Agreements (resume) -->
      <template v-if="mode === 'list'">
        <MyAgreements
          @edit="openForEdit"
          @view="openForEdit"
          @new="goToCreate"
        />
      </template>

      <!-- Edit an owned agreement -->
      <template v-else-if="mode === 'edit' && editTarget">
        <h1 class="text-lg font-semibold text-ink-800 print:hidden">Edit agreement</h1>
        <CaptureForm
          :agreement-id="editTarget.id"
          :initial-agreement="editTarget"
          @change-template="showMyAgreements"
          @saved-to-account="onSavedToAccount"
        />
      </template>

      <!-- Create a new agreement -->
      <template v-else>
        <h1 class="text-lg font-semibold text-ink-800">
          Create a Rental Agreement
        </h1>
        <p v-if="editError" class="text-sm text-red-600">{{ editError }}</p>
        <TemplatePicker v-if="!selection" @select="onSelect" />
        <CaptureForm
          v-else
          :state="selection.state"
          :type="selection.type"
          @change-template="onChangeTemplate"
          @saved-to-account="onSavedToAccount"
        />
      </template>
    </main>
  </div>
</template>
