package com.sooktin.backend.global;

import com.sooktin.backend.domain.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!local")
@ConditionalOnProperty(name = "spring.messaging.in-memory", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
class ChatMessageConsumer {  // ← 이건 아직 없음

    private final SimpMessagingTemplate messagingTemplate;

    @RabbitListener(queues = "chat.queue")
    public void handleChatMessage(ChatMessage message) {
        // RabbitMQ에서 받은 메시지를 WebSocket으로 전파
        log.info("RabbitMQ에서 메시지 수신: {}", message.getContent());
        try {
            // RabbitMQ에서 받은 메시지를 WebSocket으로 전송
            messagingTemplate.convertAndSend("/topic/chat/" + message.getRoomId(), message);
            log.info("WebSocket으로 메시지 전송 완료: /topic/chat/{}", message.getRoomId());

        } catch (Exception e) {
            log.error("Consumer에서 메시지 처리 중 오류: {}", e.getMessage(), e);
        }
    }
}
