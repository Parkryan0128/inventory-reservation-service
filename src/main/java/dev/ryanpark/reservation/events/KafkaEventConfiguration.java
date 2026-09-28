package dev.ryanpark.reservation.events;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "app.events.enabled", havingValue = "true")
public class KafkaEventConfiguration {
    @Bean NewTopic ordersTopic(@Value("${app.events.topic:orders.v1}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }
    @Bean NewTopic deadLetterTopic(@Value("${app.events.topic:orders.v1}") String topic) {
        return TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).build();
    }
    @Bean EventPublisher eventPublisher(KafkaTemplate<String, String> kafka,
                                       @Value("${app.events.topic:orders.v1}") String topic) {
        return (UUID order, String payload) -> kafka.send(topic, order.toString(), payload).get(5, TimeUnit.SECONDS);
    }
    @Bean DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        var recoverer = new DeadLetterPublishingRecoverer(kafka, (record, exception) ->
                new org.apache.kafka.common.TopicPartition(record.topic() + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000, 2));
    }
    @Bean EventListener eventListener(OrderEventConsumer consumer) { return new EventListener(consumer); }
    static class EventListener {
        private final OrderEventConsumer consumer;
        EventListener(OrderEventConsumer consumer) { this.consumer = consumer; }
        @KafkaListener(topics = "${app.events.topic:orders.v1}", groupId = "order-audit-v1")
        public void onEvent(String payload) { consumer.accept(payload); }
    }
}
