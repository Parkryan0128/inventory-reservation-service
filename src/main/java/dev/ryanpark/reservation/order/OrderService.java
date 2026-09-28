package dev.ryanpark.reservation.order;

import dev.ryanpark.reservation.common.ApiException;
import dev.ryanpark.reservation.inventory.ProductRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static dev.ryanpark.reservation.order.OrderDtos.*;

@Service
public class OrderService {
    private final ProductRepository products;
    private final ReservationRepository orders;
    private final Clock clock;
    public OrderService(ProductRepository products, ReservationRepository orders, Clock clock) {
        this.products = products; this.orders = orders; this.clock = clock;
    }

    @Transactional
    public OrderView reserve(String owner, ReserveRequest request) {
        if (owner == null || owner.isBlank() || owner.length() > 100) throw ApiException.invalid("Invalid owner");
        var product = products.lockById(request.productId()).orElseThrow(() -> ApiException.notFound("Product"));
        product.reserve(request.quantity());
        var now = clock.instant();
        return OrderView.from(orders.saveAndFlush(new Reservation(owner, product, request.quantity(), now,
                now.plus(Duration.ofMinutes(15)))));
    }

    @Transactional(readOnly = true)
    public OrderView get(String owner, UUID id) {
        var order = orders.findById(id).filter(value -> value.ownerId().equals(owner))
                .orElseThrow(() -> ApiException.notFound("Order"));
        return OrderView.from(order);
    }
}
