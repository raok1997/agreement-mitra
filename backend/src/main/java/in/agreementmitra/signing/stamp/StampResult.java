package in.agreementmitra.signing.stamp;

/**
 * The outcome of a stamp procurement: the stamp serial, its denomination (rupees) and jurisdiction,
 * whether real duty was paid, and the composited stamped PDF bytes. Vendor-neutral. For the v1
 * synthetic adapter {@code dutyPaid} is always {@code false} (no real duty paid — sandbox/dummy
 * data); the value is authoritative and is copied verbatim onto the agreement's stored stamp data.
 */
public record StampResult(
    String serial, String jurisdiction, int denomination, boolean dutyPaid, byte[] stampedPdf) {}
