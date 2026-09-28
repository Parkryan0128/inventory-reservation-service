package dev.ryanpark.reservation;

import dev.ryanpark.reservation.common.ApiException;
import dev.ryanpark.reservation.inventory.CatalogService;
import dev.ryanpark.reservation.inventory.ProductDtos.CreateProduct;
import dev.ryanpark.reservation.order.OrderDtos.ReserveRequest;
import dev.ryanpark.reservation.order.OrderService;
import dev.ryanpark.reservation.order.ReservationRepository;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class InventoryTest {
    @Autowired CatalogService catalog;
    @Autowired OrderService service;
    @Autowired ReservationRepository orders;
    @Autowired PlatformTransactionManager transactions;

    private UUID product(int stock) {
        return catalog.create(new CreateProduct("SKU-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT), "Test product", 1200, "USD", stock)).id();
    }

    @Test void reservationsSnapshotPricesAndPreserveBalance() {
        var id = product(5);
        var order = service.reserve("alice", new ReserveRequest(id, 2));
        assertThat(order.totalPriceCents()).isEqualTo(2400);
        assertThat(catalog.get(id).available()).isEqualTo(3);
        assertThat(catalog.get(id).reserved()).isEqualTo(2);
    }

    @Test void concurrentDemandCannotOversell() throws Exception {
        var id = product(25);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Boolean>>();
        try (var pool = Executors.newFixedThreadPool(20)) {
            for (int i = 0; i < 120; i++) {
                final int user = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try { service.reserve("buyer-" + user, new ReserveRequest(id, 1)); return true; }
                    catch (ApiException failure) {
                        assertThat(failure.code()).isEqualTo("INSUFFICIENT_STOCK"); return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(30, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(25);
        }
        var inventory = catalog.get(id);
        assertThat(inventory.available()).isZero();
        assertThat(inventory.reserved()).isEqualTo(25);
        assertThat(orders.countByProductId(id)).isEqualTo(25);
    }

    @Test void insufficientStockLeavesNoOrder() {
        var id = product(1);
        assertThatThrownBy(() -> service.reserve("alice", new ReserveRequest(id, 2))).isInstanceOf(ApiException.class);
        assertThat(catalog.get(id).available()).isEqualTo(1);
        assertThat(orders.countByProductId(id)).isZero();
    }

    @Test void invalidQuantityCannotIncreaseInventory() {
        var id = product(3);
        assertThatThrownBy(() -> service.reserve("alice", new ReserveRequest(id, -1))).isInstanceOf(ApiException.class);
        assertThat(catalog.get(id).available()).isEqualTo(3);
    }

    @Test void transactionRollbackRestoresInventoryAndRemovesOrder() {
        var id = product(5);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            service.reserve("alice", new ReserveRequest(id, 3));
            throw new IllegalStateException("simulated failure after write");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(catalog.get(id).available()).isEqualTo(5);
        assertThat(orders.countByProductId(id)).isZero();
    }

    @Test void differentOwnerCannotReadOrder() {
        var order = service.reserve("alice", new ReserveRequest(product(1), 1));
        assertThatThrownBy(() -> service.get("bob", order.id())).isInstanceOf(ApiException.class);
    }
}
