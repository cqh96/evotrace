package io.evotrace.server.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka listener error handling: failed records are retried with a 1s backoff
 * (3 attempts) and then published to a dead-letter topic ({@code <topic>.DLT})
 * instead of being silently dropped or stuck in a rebalance loop.
 * <p>
 * Two factories are provided:
 * <ul>
 *   <li>{@code kafkaListenerContainerFactory} — default factory for
 *       {@code evo.events.raw} (Envelope payloads, JsonDeserializer).</li>
 *   <li>{@code aiTaskListenerContainerFactory} — for {@code evo.tasks.ai}
 *       whose payloads are JSON strings (KafkaTemplate&lt;String,String&gt;),
 *       so a StringDeserializer must be used (the global JsonDeserializer
 *       default would fail to parse them).</li>
 * </ul>
 */
@Configuration
public class KafkaContainerConfig {

    @Bean
    public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, Object>> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(deadLetterErrorHandler(kafkaTemplate));
        return factory;
    }

    @SuppressWarnings("deprecation") // JsonDeserializer matches the project's JsonSerializer producer config
    @Bean
    public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, String>> aiTaskListenerContainerFactory(
            KafkaProperties properties,
            KafkaTemplate<String, Object> kafkaTemplate) {
        Map<String, Object> props = new HashMap<>(properties.buildConsumerProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // AI task messages are sent via KafkaTemplate<String, String> with the
        // JsonSerializer, i.e. the JSON-encoded task string arrives as a JSON
        // string literal — decode it back with JsonDeserializer → String.
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class.getName());
        props.put("spring.json.value.default.type", String.class.getName());

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.setCommonErrorHandler(deadLetterErrorHandler(kafkaTemplate));
        return factory;
    }

    private static DefaultErrorHandler deadLetterErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        return new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaTemplate, (ConsumerRecord<?, ?> record, Exception ex) ->
                        new org.apache.kafka.common.TopicPartition(record.topic() + ".DLT", record.partition())),
                new FixedBackOff(1000L, 3));
    }
}
