// The one message for "this link does not open the agreement". A claimed agreement and an unknown
// one are indistinguishable by design (the server answers the same 404 so ownership cannot be
// probed), so the copy hedges rather than asserts -- and it is shared between the shell's first
// read and the status view's later re-read so the two can never say different things.
export const LINK_UNAVAILABLE_MESSAGE =
  "This link no longer opens the agreement. If it has been saved to an account, sign in to open it.";
