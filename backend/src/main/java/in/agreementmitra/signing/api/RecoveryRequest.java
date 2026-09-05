package in.agreementmitra.signing.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A request to have an agreement's recovery link re-sent.
 *
 * <p>One field, deliberately. There is no destination field: the system decides who to contact from
 * what it already holds, so a caller can never redirect a message. There is no field that could
 * carry an agreement identifier either - if a caller had one they would not need this endpoint.
 *
 * @param reference the agreement's tracking reference
 */
public record RecoveryRequest(
    @NotBlank(message = "Enter your agreement reference.")
        @Size(max = 16, message = "That is not a valid agreement reference.")
        String reference) {}
