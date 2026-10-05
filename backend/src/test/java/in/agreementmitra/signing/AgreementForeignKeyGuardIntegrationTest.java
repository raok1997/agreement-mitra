package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.HarnessTestConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Every foreign key into {@code agreement} or {@code signer} is classified by the delete-draft
 * design (delete-draft-agreement, design Context): either the deletability rule excludes any
 * agreement it could hang off, or deleting the parent clears/cascades it. A new FK fails this test
 * until someone decides which - otherwise the owner's delete would quietly turn into a 500.
 *
 * <p>Out of reach: a column holding an agreement id <b>without</b> an FK (e.g. {@code
 * agreement_deletion}) is invisible to {@code information_schema} and is not checked here.
 */
@SpringBootTest
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AgreementForeignKeyGuardIntegrationTest {

  /** "table.column -> parent" mapped to how the delete handles it. */
  private static final Map<String, String> CLASSIFIED =
      Map.ofEntries(
          // the parties - cascaded by the aggregate (cascade = ALL, orphanRemoval)
          Map.entry("signer.agreement_id->agreement", "cascaded"),
          // exist only after finalise - excluded by "no signing request"
          Map.entry("signing_request.agreement_id->agreement", "excluded"),
          Map.entry("signing_request_invitee.signer_id->signer", "excluded"),
          Map.entry("signed_document_delivery.agreement_id->agreement", "excluded"),
          Map.entry("signed_document_delivery.signer_id->signer", "excluded"),
          // exist only with an order - excluded by "no payment order"
          Map.entry("payment_order.agreement_id->agreement", "excluded"),
          Map.entry("stamp_quote.agreement_id->agreement", "excluded"),
          // set only for PAID/WAIVED agreements - excluded by "payment state UNPAID"
          Map.entry("recovery_audit.agreement_id->agreement", "excluded"),
          // kept with the link cleared (V23)
          Map.entry("stamp_intake_audit.agreement_id->agreement", "set null"));

  @Autowired private JdbcTemplate jdbc;

  @Test
  void everyForeignKeyIntoAgreementOrSignerIsClassified() {
    List<String> foreignKeys =
        jdbc.queryForList(
            "SELECT kcu.table_name || '.' || kcu.column_name || '->' || ccu.table_name"
                + " FROM information_schema.referential_constraints rc"
                + " JOIN information_schema.key_column_usage kcu"
                + "   ON kcu.constraint_name = rc.constraint_name"
                + "  AND kcu.constraint_schema = rc.constraint_schema"
                + " JOIN information_schema.constraint_column_usage ccu"
                + "   ON ccu.constraint_name = rc.unique_constraint_name"
                + "  AND ccu.constraint_schema = rc.unique_constraint_schema"
                + " WHERE rc.constraint_schema = current_schema()"
                + "   AND ccu.table_name IN ('agreement', 'signer')",
            String.class);

    assertThat(foreignKeys).isNotEmpty();
    assertThat(CLASSIFIED.keySet())
        .as("an FK into agreement/signer the delete-draft design has not classified")
        .containsAll(foreignKeys);
  }
}
