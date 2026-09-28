package dev.ryanpark.reservation.order;

import static dev.ryanpark.reservation.order.OrderDtos.OrderView;
import static dev.ryanpark.reservation.order.OrderDtos.ReserveRequest;

import dev.ryanpark.reservation.common.ApiException;
import dev.ryanpark.reservation.events.OutboxRecorder;
import dev.ryanpark.reservation.inventory.ProductRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
  private final ProductRepository products;
  private final ReservationRepository orders;
  private final Clock clock;
  private final Duration ttl;
  private final OutboxRecorder events;

  public OrderService(
      ProductRepository products,
      ReservationRepository orders,
      Clock clock,
      @Value("${app.reservation-ttl:PT15M}") Duration ttl,
      OutboxRecorder events) {
    if (ttl.isNegative() || ttl.isZero())
      throw new IllegalArgumentException("Reservation TTL must be positive");
    this.products = products;
    this.orders = orders;
    this.clock = clock;
    this.ttl = ttl;
    this.events = events;
  }

  /** Internal convenience. HTTP clients must supply a stable idempotency key. */
  @Transactional
  public OrderView reserve(String owner, ReserveRequest request) {
    return reserve(owner, UUID.randomUUID().toString(), request);
  }

  @Transactional
  public OrderView reserve(String owner, String key, ReserveRequest request) {
    validate(owner, key, request);
    var existing = orders.findByOwnerIdAndIdempotencyKey(owner, key);
    if (existing.isPresent()) return replay(existing.get(), request);
    var product =
        products.lockById(request.productId()).orElseThrow(() -> ApiException.notFound("Product"));
    // A competing request may have committed while this transaction waited for the product lock.
    existing = orders.findByOwnerIdAndIdempotencyKey(owner, key);
    if (existing.isPresent()) return replay(existing.get(), request);
    product.reserve(request.quantity());
    var now = clock.instant();
    var order =
        orders.saveAndFlush(
            new Reservation(owner, key, product, request.quantity(), now, now.plus(ttl)));
    events.record(order);
    return OrderView.from(order);
  }

  static void validate(String owner, String key, ReserveRequest request) {
    if (owner == null || owner.isBlank() || owner.length() > 100)
      throw ApiException.invalid("Invalid owner");
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,80}"))
      throw ApiException.invalid("Invalid Idempotency-Key");
    if (request == null
        || request.productId() == null
        || request.quantity() < 1
        || request.quantity() > 10_000)
      throw ApiException.invalid("Product and quantity between 1 and 10000 are required");
  }

  static OrderView replay(Reservation existing, ReserveRequest request) {
    if (!existing.productId().equals(request.productId())
        || existing.quantity() != request.quantity())
      throw ApiException.conflict(
          "IDEMPOTENCY_CONFLICT", "Key was already used for a different request");
    return OrderView.from(existing);
  }

  @Transactional(readOnly = true)
  public OrderView get(String owner, UUID id) {
    return OrderView.from(
        orders
            .findById(id)
            .filter(order -> order.ownerId().equals(owner))
            .orElseThrow(() -> ApiException.notFound("Order")));
  }

  @Transactional(readOnly = true)
  public List<OrderView> list(String owner, int page) {
    if (page < 0) throw ApiException.invalid("Page cannot be negative");
    return orders.findByOwnerIdOrderByCreatedAtDesc(owner, PageRequest.of(page, 50)).stream()
        .map(OrderView::from)
        .toList();
  }

  @Transactional
  public OrderView cancel(String owner, UUID id) {
    return finish(lockOwnedReservation(owner, id), OrderStatus.CANCELLED);
  }

  @Transactional
  public OrderView payment(UUID id, boolean success) {
    var order = orders.lockById(id).orElseThrow(() -> ApiException.notFound("Order"));
    return finish(order, success ? OrderStatus.CONFIRMED : OrderStatus.PAYMENT_FAILED);
  }

  @Transactional
  public boolean expire(UUID id) {
    var order = orders.lockById(id).orElseThrow(() -> ApiException.notFound("Order"));
    if (order.status() != OrderStatus.RESERVED || clock.instant().isBefore(order.expiresAt()))
      return false;
    finish(order, OrderStatus.EXPIRED);
    return true;
  }

  private Reservation lockOwnedReservation(String owner, UUID id) {
    return orders
        .lockById(id)
        .filter(order -> order.ownerId().equals(owner))
        .orElseThrow(() -> ApiException.notFound("Order"));
  }

  private OrderView finish(Reservation order, OrderStatus requested) {
    if (order.status() == requested || order.status() == OrderStatus.EXPIRED)
      return OrderView.from(order);
    if (order.status() != OrderStatus.RESERVED)
      throw ApiException.conflict("INVALID_TRANSITION", "Order is already " + order.status());
    // Lock order is always reservation -> product. Check the deadline after any lock wait.
    var product =
        products.lockById(order.productId()).orElseThrow(() -> ApiException.notFound("Product"));
    var target = clock.instant().isBefore(order.expiresAt()) ? requested : OrderStatus.EXPIRED;
    if (target == OrderStatus.CONFIRMED) product.confirm(order.quantity());
    else product.release(order.quantity());
    order.transitionTo(target);
    events.record(order);
    return OrderView.from(order);
  }
}
