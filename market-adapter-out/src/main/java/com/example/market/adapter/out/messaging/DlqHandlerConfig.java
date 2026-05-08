package com.example.market.adapter.out.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 컨슈머의 실패 처리 — 3회 재시도(200ms) 후 DLQ topic 으로 publish.
 *
 * <p>DLQ topic 명: {@code <원본>-dlt}.</p>
 *
 * <p>현재 DLQ 메시지를 자동으로 재처리하는 endpoint 는 없다. 운영자가 Kafka 에서 직접 옮기거나
 * 별도 도구로 재처리한다 (admin replay endpoint 추가는 후속 과제).</p>
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
@ConditionalOnBean(KafkaTemplate.class)        // Kafka autoconfigure 가 활성화된 경우만
public class DlqHandlerConfig {

    @Bean
    public DefaultErrorHandler defaultErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (consumerRecord, ex) -> new org.apache.kafka.common.TopicPartition(
                        consumerRecord.topic() + "-dlt", consumerRecord.partition()));
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(200, 3));
        handler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("kafka retry attempt={} topic={} key={} reason={}",
                        deliveryAttempt, record.topic(), record.key(), ex.getMessage()));
        return handler;
    }
}
