package com.taskqueue.broker.kafka;

import java.util.Properties;
import java.util.concurrent.Future;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.model.Task;
import com.taskqueue.queue.TaskQueue;

/**
 * Optional Kafka adapter for {@link TaskQueue} — swappable via {@code @ConditionalOnProperty}.
 *
 * <p>From broker-comparison.md: "Kafka is the right choice when you need ordered delivery
 * within a partition, replay from any offset, or throughput >100k msg/s. The trade-off
 * is operational complexity (ZooKeeper / KRaft, brokers, consumer groups managed separately)."
 *
 * <p>This is a <em>sketch adapter</em> — it demonstrates the Kafka producer side.
 * The consumer side (reading tasks from Kafka) would use Spring Kafka's
 * {@code @KafkaListener} and is left for the reader to implement.
 *
 * <p>Activate by setting {@code taskqueue.queue.type=kafka} and providing
 * {@code KAFKA_BOOTSTRAP_SERVERS} env var.
 *
 * <p>From message-ordering.md: {@code task.type()} is used as the partition key
 * so all tasks of the same type land on the same partition and are processed
 * in submission order by a single consumer in the group.
 */
public class KafkaTaskQueue implements TaskQueue, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaTaskQueue.class);

    static final String TOPIC = "task-submissions";

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper mapper;

    public KafkaTaskQueue(String bootstrapServers, ObjectMapper mapper) {
        this.mapper   = mapper;
        this.producer = buildProducer(bootstrapServers);
    }

    /**
     * Sends the task to Kafka with {@code task.type()} as the partition key.
     *
     * <p>Uses {@code acks=all} and {@code enable.idempotence=true} (configured in
     * {@link #buildProducer}) so the broker deduplicates retried produces.
     */
    @Override
    public void enqueue(Task task) {
        try {
            String json = mapper.writeValueAsString(task);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC,
                    task.type(),   // partition key → per-type ordering
                    json);
            Future<RecordMetadata> future = producer.send(record, (meta, ex) -> {
                if (ex != null) {
                    log.error("[Kafka] Failed to produce task {}: {}", task.id(), ex.getMessage());
                } else {
                    log.debug("[Kafka] task={} → partition={} offset={}",
                            task.id(), meta.partition(), meta.offset());
                }
            });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize task " + task.id(), e);
        }
    }

    /**
     * Not implemented for Kafka — the consumer side uses {@code @KafkaListener}.
     * Throws {@link UnsupportedOperationException} to signal misconfiguration.
     */
    @Override
    public Task dequeue() throws InterruptedException {
        throw new UnsupportedOperationException(
                "KafkaTaskQueue does not support blocking dequeue. "
                + "Use @KafkaListener for the consumer side.");
    }

    @Override
    public int size() {
        // Kafka topic size requires AdminClient — return -1 to indicate not available
        return -1;
    }

    @Override
    public void close() {
        producer.flush();
        producer.close();
    }

    private static KafkaProducer<String, String> buildProducer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // Idempotent producer: dedup retried produces at the broker
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, "3");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");        // batch small messages
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        return new KafkaProducer<>(props);
    }
}
