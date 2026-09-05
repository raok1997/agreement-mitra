package in.agreementmitra.signing.zoop;

/**
 * What the instrument itself says about where a signature may go, measured once per request.
 *
 * <p>Every field here is <b>read off the document</b> rather than configured, and that is the
 * point: the margins belong to the {@code documents} template, and no type crosses that module
 * boundary. A constant copied from the template's CSS into this adapter would go stale silently the
 * first time the layout changed - and a placement bug is invisible until someone looks at a signed
 * page.
 *
 * @param pageCount how many pages the instrument has
 * @param smallestPage the smallest page, rotation-adjusted; a placement applied to several pages
 *     has to clear the shortest and narrowest of them
 * @param contentLeft the left edge of the body text column, in points from the left
 * @param contentRight the right edge of the body text column, in points from the left
 */
record DocumentGeometry(
    int pageCount, AnchorPosition.PageBox smallestPage, float contentLeft, float contentRight) {}
