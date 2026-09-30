package dev.ryanpark.reservation.events;

import dev.ryanpark.reservation.inventory.ProductDtos.ProductView;
import dev.ryanpark.reservation.order.OrderDtos.OrderView;

/**
 * Immutable transaction snapshots for local observers; the outbox remains the durable event path.
 */
public record OrderChanged(ProductView inventory, OrderView order) {}
