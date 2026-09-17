package in.agreementmitra.rules;

/**
 * Identifies the stamp paper catalog a quote's stamp plans were computed against. Versioned
 * independently of duty rules: what a treasury issues is not duty law.
 *
 * @param offer how this state's stamp options are offered to the customer
 */
public record CatalogRef(String state, String source, String contentHash, StampOffer offer) {

  public CatalogRef {
    offer = offer == null ? StampOffer.PLANNED : offer;
  }
}
