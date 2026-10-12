package in.agreementmitra.signing.payment;

import in.agreementmitra.signing.AgreementPaymentStanding;
import in.agreementmitra.signing.PaymentState;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.api.PaymentOrdersResponse;
import in.agreementmitra.signing.api.PaymentOrdersResponse.AgreementPayment;
import in.agreementmitra.signing.api.PaymentOrdersResponse.Entry;
import in.agreementmitra.signing.api.PaymentOrdersResponse.Group;
import in.agreementmitra.signing.payment.PaymentEventLedger.Attempt;
import in.agreementmitra.signing.payment.PaymentOrderRepository.ListedOrder;
import in.agreementmitra.signing.payment.PaymentOrderRepository.PaymentHolder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff orders view's read: payment orders that hold a captured payment nobody recorded, and
 * recent orders that were not paid, each with the ledger attempts behind it. It writes nothing and
 * calls no gateway.
 */
@Service
public class PaymentOrderOverview {

  /**
   * The statuses listed. An explicit list, not "every status but {@code PAID}": a status added
   * later is left out until someone decides to list it.
   */
  static final List<PaymentOrderStatus> LISTED_STATUSES =
      List.of(PaymentOrderStatus.CREATED, PaymentOrderStatus.FAILED, PaymentOrderStatus.EXPIRED);

  /** How far back the not-paid group looks. The stuck group has no date limit. */
  static final Duration NOT_PAID_WINDOW = Duration.ofDays(30);

  /** The most entries a group returns. */
  static final int GROUP_CAP = 200;

  private final PaymentOrderRepository repository;
  private final PaymentEventLedger ledger;
  private final AgreementService agreementService;

  PaymentOrderOverview(
      PaymentOrderRepository repository,
      PaymentEventLedger ledger,
      AgreementService agreementService) {
    this.repository = repository;
    this.ledger = ledger;
    this.agreementService = agreementService;
  }

  /**
   * Both groups, read from one snapshot.
   *
   * <p>Repeatable read, because the ledger commits in its own transaction: at the default level an
   * attempt committed between the two order reads would leave its order in neither group. Spring
   * applies the isolation level only to a transaction this method starts, so it must stay the
   * outermost transactional call.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PaymentOrdersResponse overview() {
    List<String> attention =
        PaymentOrderQueryAdapter.ATTENTION_OUTCOMES.stream().map(Enum::name).toList();
    List<String> listed = LISTED_STATUSES.stream().map(Enum::name).toList();
    // One past the cap: the extra row says the group was cut short, and is dropped.
    List<ListedOrder> stuck = repository.findStuck(attention, listed, GROUP_CAP + 1);
    List<ListedOrder> notPaid =
        repository.findNotPaid(
            attention, listed, Instant.now().minus(NOT_PAID_WINDOW), GROUP_CAP + 1);
    List<ListedOrder> orders = Stream.concat(capped(stuck), capped(notPaid)).toList();
    if (orders.isEmpty()) {
      // No further read: an empty IN () is a syntax error in Postgres.
      return new PaymentOrdersResponse(new Group(List.of(), false), new Group(List.of(), false));
    }

    Map<String, List<Attempt>> attempts =
        ledger.attemptsFor(orders.stream().map(ListedOrder::getProviderOrderId).toList()).stream()
            .collect(Collectors.groupingBy(Attempt::providerOrderId));
    Holders holders = holdersOf(attempts);
    Map<UUID, AgreementPaymentStanding> standings =
        agreementService.paymentStandings(
            Stream.concat(
                    orders.stream().map(ListedOrder::getAgreementId),
                    holders.byOrder().stream().map(PaymentHolder::getAgreementId))
                .collect(Collectors.toCollection(LinkedHashSet::new)));

    return new PaymentOrdersResponse(
        group(stuck, attempts, holders, standings), group(notPaid, attempts, holders, standings));
  }

  private static Stream<ListedOrder> capped(List<ListedOrder> rows) {
    return rows.stream().limit(GROUP_CAP);
  }

  private static Group group(
      List<ListedOrder> rows,
      Map<String, List<Attempt>> attempts,
      Holders holders,
      Map<UUID, AgreementPaymentStanding> standings) {
    List<Entry> entries =
        capped(rows)
            .map(
                order ->
                    entry(
                        order,
                        attempts.getOrDefault(order.getProviderOrderId(), List.of()),
                        holders,
                        standings))
            .toList();
    return new Group(entries, rows.size() > GROUP_CAP);
  }

  private static Entry entry(
      ListedOrder order,
      List<Attempt> attempts,
      Holders holders,
      Map<UUID, AgreementPaymentStanding> standings) {
    AgreementPaymentStanding standing = standings.get(order.getAgreementId());
    return new Entry(
        standing.trackingReference(),
        order.getPaymentOrderId(),
        order.getStatus(),
        order.getAmountMinorUnits(),
        order.getCurrency(),
        order.getCreatedAt(),
        agreementPayment(standing, attempts),
        attempts.stream().filter(Attempt::isStuck).map(Attempt::outcome).distinct().toList(),
        heldBy(order, attempts, holders, standings),
        attempts.stream().map(PaymentOrderOverview::response).toList());
  }

  private static AgreementPayment agreementPayment(
      AgreementPaymentStanding standing, List<Attempt> attempts) {
    if (standing.paymentState() == PaymentState.UNPAID) {
      return AgreementPayment.UNPAID;
    }
    if (standing.paymentState() == PaymentState.WAIVED) {
      return AgreementPayment.WAIVED;
    }
    boolean thisPayment =
        attempts.stream()
            .map(Attempt::providerPaymentId)
            .filter(Objects::nonNull)
            .anyMatch(standing::paidUnder);
    return thisPayment ? AgreementPayment.PAID_THIS_PAYMENT : AgreementPayment.PAID_OTHER_PAYMENT;
  }

  /**
   * Who already holds each payment id a listed order was refused for: an agreement recorded under
   * it, or another payment order recorded under it. Both lookups are skipped when no attempt was
   * refused that way.
   */
  private Holders holdersOf(Map<String, List<Attempt>> attempts) {
    Set<String> reused =
        attempts.values().stream()
            .flatMap(List::stream)
            .filter(Attempt::isStuckReuse)
            .map(Attempt::providerPaymentId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (reused.isEmpty()) {
      return new Holders(Map.of(), List.of());
    }
    return new Holders(
        agreementService.trackingReferencesHolding(reused), repository.findHolding(reused));
  }

  private static List<String> heldBy(
      ListedOrder order,
      List<Attempt> attempts,
      Holders holders,
      Map<UUID, AgreementPaymentStanding> standings) {
    Set<String> trackingReferences = new LinkedHashSet<>();
    attempts.stream()
        .filter(Attempt::isStuckReuse)
        .map(Attempt::providerPaymentId)
        .distinct()
        .forEach(
            paymentId -> {
              String agreementHolder = holders.byAgreement().get(paymentId);
              if (agreementHolder != null) {
                trackingReferences.add(agreementHolder);
              }
              holders.byOrder().stream()
                  .filter(holder -> paymentId.equals(holder.getProviderPaymentId()))
                  // The order itself is not a holder of the payment it was refused for.
                  .filter(holder -> !order.getPaymentOrderId().equals(holder.getPaymentOrderId()))
                  .map(holder -> standings.get(holder.getAgreementId()).trackingReference())
                  .forEach(trackingReferences::add);
            });
    return new ArrayList<>(trackingReferences);
  }

  private static PaymentOrdersResponse.Attempt response(Attempt attempt) {
    return new PaymentOrdersResponse.Attempt(
        attempt.source(),
        attempt.outcome(),
        attempt.providerPaymentId(),
        attempt.amountMinorUnits(),
        attempt.currency(),
        attempt.refusedRule(),
        attempt.sqlState(),
        attempt.firstReceivedAt(),
        attempt.lastReceivedAt(),
        attempt.attemptCount());
  }

  /**
   * @param byAgreement gateway payment id to the tracking reference of the agreement recorded under
   *     it
   * @param byOrder payment orders recorded under one of the ids
   */
  private record Holders(Map<String, String> byAgreement, List<PaymentHolder> byOrder) {}
}
