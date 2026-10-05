package in.agreementmitra.identity.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.HarnessTestConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The binding condition in both consume UPDATEs, against real Postgres (login-browser-binding D2):
 * the right binding hash consumes the row; a wrong one, or a row with no binding at all, consumes
 * nothing and leaves {@code consumed_at} null. Rows are written by raw JDBC so a null binding can
 * be planted -- {@link HandoffService#issue} rightly refuses one.
 *
 * <p>Same annotation set as {@code IdentitySchemaValidateIntegrationTest}, so the cached context is
 * reused rather than a slice context started for this class alone.
 */
@SpringBootTest
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class LoginBindingConsumeIntegrationTest {

  @Autowired private OauthLoginStateRepository loginStates;
  @Autowired private LoginHandoffRepository handoffs;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate tx;

  @Test
  void aLoginStateIsConsumedOnlyWithItsBinding() {
    String state = insertState("bound-binding");

    assertThat(consumeState(state, "other-binding")).isZero();
    assertThat(consumedAt("oauth_login_state", "state_hash", state)).isNull();

    assertThat(consumeState(state, "bound-binding")).isEqualTo(1);
    assertThat(consumedAt("oauth_login_state", "state_hash", state)).isNotNull();
  }

  @Test
  void aLoginStateWithNoBindingIsNeverConsumed() {
    String state = insertState(null);

    assertThat(consumeState(state, "any-binding")).isZero();
    assertThat(consumedAt("oauth_login_state", "state_hash", state)).isNull();
  }

  @Test
  void aHandoffIsConsumedOnlyWithItsBinding() {
    String handoff = insertHandoff("bound-binding");

    assertThat(consumeHandoff(handoff, "other-binding")).isZero();
    assertThat(consumedAt("login_handoff", "handoff_hash", handoff)).isNull();

    assertThat(consumeHandoff(handoff, "bound-binding")).isEqualTo(1);
    assertThat(consumedAt("login_handoff", "handoff_hash", handoff)).isNotNull();
  }

  @Test
  void aHandoffWithNoBindingIsNeverConsumed() {
    String handoff = insertHandoff(null);

    assertThat(consumeHandoff(handoff, "any-binding")).isZero();
    assertThat(consumedAt("login_handoff", "handoff_hash", handoff)).isNull();
  }

  private int consumeState(String stateHash, String bindingHash) {
    return tx.execute(status -> loginStates.consume(stateHash, bindingHash, Instant.now()));
  }

  private int consumeHandoff(String handoffHash, String bindingHash) {
    return tx.execute(status -> handoffs.consume(handoffHash, bindingHash, Instant.now()));
  }

  private String insertState(String bindingHash) {
    String stateHash = "state-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO oauth_login_state "
            + "(id, state_hash, code_verifier, created_at, expires_at, browser_binding_hash) "
            + "VALUES (?, ?, 'verifier', now(), now() + interval '5 minutes', ?)",
        UUID.randomUUID(),
        stateHash,
        bindingHash);
    return stateHash;
  }

  private String insertHandoff(String bindingHash) {
    UUID identityId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO identity (id, display_name, created_at) VALUES (?, 'T binding', now())",
        identityId);
    String handoffHash = "handoff-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO login_handoff "
            + "(id, handoff_hash, identity_id, created_at, expires_at, browser_binding_hash) "
            + "VALUES (?, ?, ?, now(), now() + interval '1 minute', ?)",
        UUID.randomUUID(),
        handoffHash,
        identityId,
        bindingHash);
    return handoffHash;
  }

  private Timestamp consumedAt(String table, String hashColumn, String hash) {
    return jdbc.queryForObject(
        "SELECT consumed_at FROM " + table + " WHERE " + hashColumn + " = ?",
        Timestamp.class,
        hash);
  }
}
