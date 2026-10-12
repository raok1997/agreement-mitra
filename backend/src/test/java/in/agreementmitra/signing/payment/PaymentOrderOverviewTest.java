package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.agreementmitra.signing.AgreementPaymentStanding;
import in.agreementmitra.signing.PaymentState;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.api.PaymentOrdersResponse;
import in.agreementmitra.signing.api.PaymentOrdersResponse.AgreementPayment;
import in.agreementmitra.signing.api.PaymentOrdersResponse.Entry;
import in.agreementmitra.signing.payment.PaymentEventLedger.Attempt;
import in.agreementmitra.signing.payment.PaymentOrderRepository.ListedOrder;
import in.agreementmitra.signing.payment.PaymentOrderRepository.PaymentHolder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * How the orders view assembles what its reads return: the cap, which attempts make an order stuck,
 * who holds a reused payment id, and the agreement's payment state. The reads themselves are
 * exercised against Postgres in the {@code StaffPaymentOrders*IntegrationTest}s.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentOrderOverviewTest {

  private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

  @Mock private PaymentOrderRepository repository;
  @Mock private PaymentEventLedger ledger;
  @Mock private AgreementService agreementService;

  private final List<ListedOrder> stuck = new ArrayList<>();
  private final List<ListedOrder> notPaid = new ArrayList<>();
  private final List<Attempt> attempts = new ArrayList<>();
  private final Map<UUID, AgreementPaymentStanding> standings = new LinkedHashMap<>();

  private record Order(UUID id, UUID agreementId) implements ListedOrder {

    static Order placedFor(UUID agreementId) {
      return new Order(UUID.randomUUID(), agreementId);
    }

    @Override
    public UUID getPaymentOrderId() {
      return id;
    }

    @Override
    public UUID getAgreementId() {
      return agreementId;
    }

    @Override
    public String getProviderOrderId() {
      return "order_" + id;
    }

    @Override
    public String getStatus() {
      return "CREATED";
    }

    @Override
    public long getAmountMinorUnits() {
      return 49900;
    }

    @Override
    public String getCurrency() {
      return "INR";
    }

    @Override
    public Instant getCreatedAt() {
      return T0;
    }
  }

  private record Holder(UUID paymentOrderId, String providerPaymentId, UUID agreementId)
      implements PaymentHolder {

    @Override
    public UUID getPaymentOrderId() {
      return paymentOrderId;
    }

    @Override
    public String getProviderPaymentId() {
      return providerPaymentId;
    }

    @Override
    public UUID getAgreementId() {
      return agreementId;
    }
  }

  private record Standing(String trackingReference, PaymentState paymentState, String paidUnderId)
      implements AgreementPaymentStanding {

    @Override
    public boolean paidUnder(String reference) {
      return paymentState == PaymentState.PAID && reference.equals(paidUnderId);
    }
  }

  @BeforeEach
  void wire() {
    when(repository.findStuck(any(), any(), anyInt())).thenReturn(stuck);
    when(repository.findNotPaid(any(), any(), any(), anyInt())).thenReturn(notPaid);
    when(ledger.attemptsFor(any())).thenReturn(attempts);
    when(agreementService.paymentStandings(any())).thenReturn(standings);
  }

  private PaymentOrdersResponse overview() {
    return new PaymentOrderOverview(repository, ledger, agreementService).overview();
  }

  /** An order on an unpaid agreement of its own. */
  private Order order() {
    return orderOn(new Standing("REF" + standings.size(), PaymentState.UNPAID, null));
  }

  private Order orderOn(AgreementPaymentStanding standing) {
    UUID agreementId = UUID.randomUUID();
    standings.put(agreementId, standing);
    return Order.placedFor(agreementId);
  }

  private void attempt(Order order, String outcome, String paymentId, Instant at) {
    attempts.add(
        new Attempt(
            order.getProviderOrderId(),
            "WEBHOOK",
            outcome,
            paymentId,
            49900,
            "INR",
            null,
            null,
            at,
            at,
            1));
  }

  @Test
  void theReadsAreAskedForTheAttentionOutcomesTheListedStatusesAndOnePastTheCap() {
    overview();

    List<String> attention =
        List.of("RECORDING_FAILED", "DUPLICATE_REFERENCE", "AMOUNT_MISMATCH", "ERRORED");
    List<String> listed = List.of("CREATED", "FAILED", "EXPIRED");
    verify(repository)
        .findStuck(argThatHolds(attention), eq(listed), eq(PaymentOrderOverview.GROUP_CAP + 1));
    verify(repository)
        .findNotPaid(
            argThatHolds(attention), eq(listed), any(), eq(PaymentOrderOverview.GROUP_CAP + 1));
    assertThat(PaymentOrderOverview.GROUP_CAP).isEqualTo(200);
  }

  private static Collection<String> argThatHolds(List<String> expected) {
    return org.mockito.ArgumentMatchers.argThat(
        actual -> actual.size() == expected.size() && actual.containsAll(expected));
  }

  @Test
  void aGroupOverTheCapKeepsTheFirstTwoHundredInOrderAndSaysItWasCutShort() {
    IntStream.range(0, 201).forEach(i -> notPaid.add(order()));

    PaymentOrdersResponse response = overview();

    assertThat(response.notPaid().truncated()).isTrue();
    assertThat(response.notPaid().entries())
        .extracting(Entry::paymentOrderId)
        .containsExactlyElementsOf(
            notPaid.subList(0, 200).stream().map(ListedOrder::getPaymentOrderId).toList());
    assertThat(response.stuck().truncated()).isFalse();
  }

  @Test
  void aGroupWithinTheCapIsComplete() {
    IntStream.range(0, 3).forEach(i -> notPaid.add(order()));

    PaymentOrdersResponse response = overview();

    assertThat(response.notPaid().entries()).hasSize(3);
    assertThat(response.notPaid().truncated()).isFalse();
  }

  @Test
  void noListedOrderGivesTwoEmptyGroupsAndReadsNothingElse() {
    PaymentOrdersResponse response = overview();

    assertThat(response.stuck().entries()).isEmpty();
    assertThat(response.stuck().truncated()).isFalse();
    assertThat(response.notPaid().entries()).isEmpty();
    assertThat(response.notPaid().truncated()).isFalse();
    verifyNoInteractions(ledger, agreementService);
    verify(repository, never()).findHolding(any());
  }

  @Test
  void attemptsAreAttachedToTheirOwnOrderInTheOrderTheLedgerGaveThem() {
    Order first = order();
    Order second = order();
    stuck.add(first);
    notPaid.add(second);
    attempt(first, "MISSING_REFERENCE", null, T0);
    attempt(second, "ALREADY_CONFIRMED", "pay_other", T0.plusSeconds(1));
    attempt(first, "RECORDING_FAILED", "pay_first", T0.plusSeconds(2));

    PaymentOrdersResponse response = overview();

    assertThat(response.stuck().entries().get(0).attempts())
        .extracting(PaymentOrdersResponse.Attempt::outcome)
        .containsExactly("MISSING_REFERENCE", "RECORDING_FAILED");
    assertThat(response.notPaid().entries().get(0).attempts())
        .extracting(PaymentOrdersResponse.Attempt::providerPaymentId)
        .containsExactly("pay_other");
  }

  @Test
  void stuckOutcomesAreDistinctAndOnlyFromAttemptsCarryingAPaymentId() {
    Order order = order();
    stuck.add(order);
    attempt(order, "RECORDING_FAILED", "pay_1", T0);
    attempt(order, "RECORDING_FAILED", "pay_1", T0.plusSeconds(1));
    attempt(order, "DUPLICATE_REFERENCE", "pay_1", T0.plusSeconds(2));
    attempt(order, "AMOUNT_MISMATCH", null, T0.plusSeconds(3));
    attempt(order, "MISSING_REFERENCE", "pay_1", T0.plusSeconds(4));

    Entry entry = overview().stuck().entries().get(0);

    assertThat(entry.stuckOutcomes()).containsExactly("RECORDING_FAILED", "DUPLICATE_REFERENCE");
    assertThat(entry.attempts()).hasSize(5);
  }

  @Test
  void anOutcomeThisBuildDoesNotKnowIsPassedThroughAndIsNotStuck() {
    Order order = order();
    notPaid.add(order);
    attempt(order, "FAILED_AT_GATEWAY", "pay_1", T0);

    Entry entry = overview().notPaid().entries().get(0);

    assertThat(entry.stuckOutcomes()).isEmpty();
    assertThat(entry.attempts())
        .extracting(PaymentOrdersResponse.Attempt::outcome)
        .containsExactly("FAILED_AT_GATEWAY");
  }

  @Test
  void aReusedPaymentIdNamesTheAgreementAndTheOtherOrderHoldingItButNotItsOwnOrder() {
    Order refused = order();
    stuck.add(refused);
    attempt(refused, "DUPLICATE_REFERENCE", "pay_reused", T0);
    UUID otherAgreement = UUID.randomUUID();
    standings.put(otherAgreement, new Standing("HELDBYORDER", PaymentState.PAID, "pay_reused"));
    when(agreementService.trackingReferencesHolding(Set.of("pay_reused")))
        .thenReturn(Map.of("pay_reused", "HELDBYAGREEMENT"));
    when(repository.findHolding(any()))
        .thenReturn(
            List.of(
                new Holder(UUID.randomUUID(), "pay_reused", otherAgreement),
                new Holder(refused.getPaymentOrderId(), "pay_reused", refused.getAgreementId())));

    Entry entry = overview().stuck().entries().get(0);

    assertThat(entry.heldBy()).containsExactly("HELDBYAGREEMENT", "HELDBYORDER");
  }

  @Test
  void aHolderNamedByBothLookupsIsListedOnce() {
    Order refused = order();
    stuck.add(refused);
    attempt(refused, "DUPLICATE_REFERENCE", "pay_reused", T0);
    UUID otherAgreement = UUID.randomUUID();
    standings.put(otherAgreement, new Standing("HOLDER", PaymentState.PAID, "pay_reused"));
    when(agreementService.trackingReferencesHolding(any()))
        .thenReturn(Map.of("pay_reused", "HOLDER"));
    when(repository.findHolding(any()))
        .thenReturn(List.of(new Holder(UUID.randomUUID(), "pay_reused", otherAgreement)));

    assertThat(overview().stuck().entries().get(0).heldBy()).containsExactly("HOLDER");
  }

  @Test
  void otherKindsCarryNoHolderAndNeitherLookupRuns() {
    Order order = order();
    stuck.add(order);
    attempt(order, "RECORDING_FAILED", "pay_1", T0);
    // Refused as a reuse, but without a payment id there is nothing to look up.
    attempt(order, "DUPLICATE_REFERENCE", null, T0.plusSeconds(1));

    Entry entry = overview().stuck().entries().get(0);

    assertThat(entry.heldBy()).isEmpty();
    verify(agreementService, never()).trackingReferencesHolding(any());
    verify(repository, never()).findHolding(any());
  }

  @Test
  void theAgreementPaymentIsUnpaidWaivedOrPaidUnderThisOrAnotherPayment() {
    Order unpaid = orderOn(new Standing("A", PaymentState.UNPAID, null));
    Order waived = orderOn(new Standing("B", PaymentState.WAIVED, null));
    Order paidThis = orderOn(new Standing("C", PaymentState.PAID, "pay_this"));
    Order paidOther = orderOn(new Standing("D", PaymentState.PAID, "pay_elsewhere"));
    Order paidNoAttempt = orderOn(new Standing("E", PaymentState.PAID, "pay_elsewhere"));
    stuck.addAll(List.of(unpaid, waived, paidThis, paidOther));
    notPaid.add(paidNoAttempt);
    attempt(unpaid, "ERRORED", "pay_u", T0);
    attempt(waived, "ERRORED", "pay_w", T0);
    attempt(paidThis, "MISSING_REFERENCE", null, T0);
    attempt(paidThis, "RECORDING_FAILED", "pay_this", T0.plusSeconds(1));
    attempt(paidOther, "RECORDING_FAILED", "pay_mine", T0);

    PaymentOrdersResponse response = overview();

    assertThat(response.stuck().entries())
        .extracting(Entry::trackingReference, Entry::agreementPayment)
        .containsExactly(
            tuple("A", AgreementPayment.UNPAID),
            tuple("B", AgreementPayment.WAIVED),
            tuple("C", AgreementPayment.PAID_THIS_PAYMENT),
            tuple("D", AgreementPayment.PAID_OTHER_PAYMENT));
    assertThat(response.notPaid().entries().get(0).agreementPayment())
        .isEqualTo(AgreementPayment.PAID_OTHER_PAYMENT);
  }

  @Test
  void anAttemptIsStuckOnlyWithAnAttentionOutcomeAndAPaymentIdAndHidesTheIdInToString() {
    Attempt stuckAttempt =
        new Attempt("order_1", "WEBHOOK", "ERRORED", "pay_secret", 1, "INR", null, null, T0, T0, 1);
    Attempt withoutId =
        new Attempt("order_1", "WEBHOOK", "ERRORED", null, 1, "INR", null, null, T0, T0, 1);
    Attempt confirmed =
        new Attempt("order_1", "WEBHOOK", "CONFIRMED", "pay_x", 1, "INR", null, null, T0, T0, 1);

    assertThat(stuckAttempt.isStuck()).isTrue();
    assertThat(stuckAttempt.isStuckReuse()).isFalse();
    assertThat(withoutId.isStuck()).isFalse();
    assertThat(confirmed.isStuck()).isFalse();
    assertThat(stuckAttempt.toString()).doesNotContain("pay_secret");
    assertThat(overviewAttemptToString()).doesNotContain("pay_secret");
  }

  private static String overviewAttemptToString() {
    return new PaymentOrdersResponse.Attempt(
            "WEBHOOK", "ERRORED", "pay_secret", 1, "INR", null, null, T0, T0, 1)
        .toString();
  }
}
