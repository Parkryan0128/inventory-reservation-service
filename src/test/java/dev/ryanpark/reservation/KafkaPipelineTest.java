package dev.ryanpark.reservation;

import dev.ryanpark.reservation.events.*;
import dev.ryanpark.reservation.inventory.*;
import dev.ryanpark.reservation.inventory.ProductDtos.CreateProduct;
import dev.ryanpark.reservation.order.*;
import dev.ryanpark.reservation.order.OrderDtos.ReserveRequest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {"app.events.enabled=true", "spring.datasource.url=jdbc:h2:mem:kafka_pipeline;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000"})
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 3, topics = {"orders.v1", "orders.v1.DLT"}, kraft = true,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = {"offsets.topic.replication.factor=1", "transaction.state.log.replication.factor=1", "transaction.state.log.min.isr=1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaPipelineTest {
    @Autowired CatalogService catalog;
    @Autowired OrderService orders;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxRelay relay;
    @Autowired ProcessedEventRepository receipts;
    @Autowired KafkaTemplate<String, String> kafka;
    @Test void actualBrokerDeliversEventsAndConsumerDeduplicatesReplay() throws Exception {
        var product = catalog.create(new CreateProduct("KAFKA-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT), "Kafka product", 500, "USD", 3));
        var order = orders.reserve("alice", new ReserveRequest(product.id(), 1));
        var reserved = outbox.findByOrderIdOrderByRevisionAsc(order.id()).getFirst();
        assertThat(relay.publish(reserved.id())).isTrue();
        kafka.send("orders.v1", order.id().toString(), reserved.payload()).get(15, TimeUnit.SECONDS);
        orders.payment(order.id(), true);
        var confirmed = outbox.findByOrderIdOrderByRevisionAsc(order.id()).get(1);
        assertThat(relay.publish(confirmed.id())).isTrue();
        // Confirmation follows the duplicate on the same Kafka key/partition, serving as a processing barrier.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(receipts.existsById(confirmed.id())).isTrue();
            assertThat(receipts.countByOrderId(order.id())).isEqualTo(2);
        });
    }
}
