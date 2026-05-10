package com.sooktin.backend.kafka;

import com.sooktin.backend.domain.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class KafkaChatProducer {

    private final KafkaTemplate<String, ChatMessage> kafkaTemplate;

    @Value("${chat.kafka.topic.messages:chat.messages}")
    private String chatMessagesTopic;

    @Value("${chat.kafka.topic.events:chat.events}")
    private String chatEventsTopic;

    /**
     * 채팅 메시지를 Kafka로 전송
     * roomId를 파티션 키로 사용하여 같은 방의 메시지는 순서 보장
     */
    public void sendMessage(ChatMessage message) {
        String key = message.getRoomId();  // 파티션 키 = roomId (순서 보장)

        CompletableFuture<SendResult<String, ChatMessage>> future =
                kafkaTemplate.send(chatMessagesTopic, key, message);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Kafka 메시지 전송 성공 - Topic: {}, Partition: {}, Offset: {}, RoomId: {}",
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        message.getRoomId());
            } else {
                log.error("Kafka 메시지 전송 실패 - RoomId: {}, Error: {}",
                        message.getRoomId(), ex.getMessage(), ex);
            }
        });
    }

    /**
     * 채팅 이벤트 전송 (입장, 퇴장, 읽음 등)
     */
    public void sendEvent(ChatMessage event) {
        String key = event.getRoomId();

        CompletableFuture<SendResult<String, ChatMessage>> future =
                kafkaTemplate.send(chatEventsTopic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Kafka 이벤트 전송 성공 - Type: {}, RoomId: {}",
                        event.getType(), event.getRoomId());
            } else {
                log.error("Kafka 이벤트 전송 실패 - Type: {}, RoomId: {}, Error: {}",
                        event.getType(), event.getRoomId(), ex.getMessage(), ex);
            }
        });
    }

    /**
     * 동기식 메시지 전송 (응답 대기 필요시)
     */
    public SendResult<String, ChatMessage> sendMessageSync(ChatMessage message) {
        try {
            return kafkaTemplate.send(chatMessagesTopic, message.getRoomId(), message).get();
        } catch (Exception e) {
            log.error("Kafka 동기 메시지 전송 실패 - RoomId: {}", message.getRoomId(), e);
            throw new RuntimeException("메시지 전송 실패", e);
        }
    }
}
