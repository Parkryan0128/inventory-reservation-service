package dev.inventory.events;

import dev.inventory.inventory.ProductDtos.ProductView;
import dev.inventory.order.OrderDtos.OrderView;

/**
 * Immutable transaction snapshots for local observers; the outbox remains the durable event path.
 */
public record OrderChanged(ProductView inventory, OrderView order) {}
