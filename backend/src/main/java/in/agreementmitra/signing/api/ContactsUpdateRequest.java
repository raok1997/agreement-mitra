package in.agreementmitra.signing.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Contacts for the parties on one agreement, and <b>nothing else</b>.
 *
 * <p>Deliberately not a subset of {@link CreateAgreementRequest}. The pre-payment contact step is
 * reached by an anonymous customer holding the agreement identifier, so the route it posts to is
 * open to any such caller - which makes the shape of this record a security boundary. It carries no
 * rent, no dates, no property address, and no party list, so a caller who reaches the route cannot
 * rewrite what was agreed or introduce a party. Parties are addressed by their existing id; an id
 * that does not belong to the agreement is rejected rather than added.
 *
 * @param contacts one entry per party being updated; at least one
 */
public record ContactsUpdateRequest(
    @NotNull(message = "Provide contact details for at least one party.")
        @NotEmpty(message = "Provide contact details for at least one party.")
        @Size(max = 20, message = "An agreement can have between 1 and 20 people.")
        @Valid
        List<PartyContact> contacts) {

  /**
   * @param signerId the party this belongs to; must already be on the agreement
   * @param email the party's email; blank clears it
   * @param mobile the party's mobile; blank clears it
   */
  public record PartyContact(
      @NotNull(message = "Each contact must identify a party.") UUID signerId,
      @Email(message = "Enter a valid email address.")
          @Size(max = 254, message = "That email address is too long.")
          String email,
      @Pattern(regexp = "|\\+?[0-9]{6,15}", message = "Enter a valid mobile number.")
          String mobile) {}
}
