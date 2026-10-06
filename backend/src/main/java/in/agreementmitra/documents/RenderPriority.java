package in.agreementmitra.documents;

/**
 * Which render slots a render may take (anonymous-surface-abuse-controls D6). Part of the module's
 * public API.
 */
public enum RenderPriority {
  /** Every preview and draft render: only the general slots. The default. */
  STANDARD,
  /**
   * The render that fulfils a paid agreement's e-stamp: the reserved slots as well as the general
   * ones, so an anonymous render flood cannot fast-fail paid fulfilment.
   */
  FULFILMENT
}
