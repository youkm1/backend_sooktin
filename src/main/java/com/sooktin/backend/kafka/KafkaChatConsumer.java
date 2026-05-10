package com.sooktin.backend.kafka;

import com.sooktin.backend.domain.ChatMessage;
import com.sooktin.backend.domain.ChatRoom;
import com.sooktin.backend.domain.User;
import com.sooktin.backend.domain.UserChatRoom;
import com.sooktin.backend.repository.ChatRepository;
import com.sooktin.backend.repository.ChatRoomRepository;
import com.sooktin.backend.repository.UserChatRoomRepository;
import com.sooktin.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class KafkaChatConsumer {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatRepository chatRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserChatRoomRepository userChatRoomRepository;
    private final UserRepository userRepository;

    /**
     * 채팅 메시지 소비 및 WebSocket 브로드캐스트
     */
    @KafkaListener(
            topics = "${chat.kafka.topic.messages:chat.messages}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeMessage(ConsumerRecord<String, ChatMessage> record, Acknowledgment ack) {
        ChatMessage message = record.value();
        String roomId = message != null && message.getRoomId() != null ? message.getRoomId() : record.key();

        try {
            if (message == null) {
                log.warn("Kafka 메시지 payload가 비어 있습니다. Offset: {}", record.offset());
                ack.acknowledge();
                return;
            }

            log.info("Kafka 메시지 수신 - Partition: {}, Offset: {}, RoomId: {}, Sender: {}",
                    record.partition(), record.offset(), roomId, message.getSender());

            ChatRoom room = getChatRoom(roomId);
            User sender = getSender(message.getSender());
            UserChatRoom senderRoom = userChatRoomRepository.findByUserAndRoom(sender, room)
                    .orElseThrow(() -> new IllegalArgumentException("발신자가 채팅방 멤버가 아닙니다."));

            // 1. DB에 메시지 저장 (아직 저장되지 않은 경우)
            if (message.getMessageId() == null) {
                message = chatRepository.save(message);
                log.debug("메시지 DB 저장 완료 - MessageId: {}", message.getMessageId());
            }

            // 2. 채팅방 마지막 메시지 정보 업데이트
            updateChatRoomLastMessage(room, message);

            // 3. 읽지 않은 메시지 카운트 증가 (발신자 제외)
            incrementUnreadCount(room, sender);
            senderRoom.markAsRead(message.getMessageId());

            // 4. WebSocket으로 클라이언트에게 브로드캐스트
            String destination = "/topic/chat/" + roomId;
            messagingTemplate.convertAndSend(destination, message);
            log.info("WebSocket 브로드캐스트 완료 - Destination: {}", destination);

            // 5. 메시지 처리 완료 확인
            ack.acknowledge();

        } catch (IllegalArgumentException e) {
            log.warn("잘못된 Kafka 메시지 폐기 - RoomId: {}, Offset: {}, Error: {}",
                    roomId, record.offset(), e.getMessage());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("메시지 처리 실패 - RoomId: {}, Offset: {}, Error: {}",
                    roomId, record.offset(), e.getMessage(), e);
            // 실패 시 ack하지 않음 → 재시도
        }
    }

    /**
     * 채팅 이벤트 소비 (입장, 퇴장, 시스템 메시지 등)
     */
    @KafkaListener(
            topics = "${chat.kafka.topic.events:chat.events}",
            groupId = "${spring.kafka.consumer.group-id}-events",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeEvent(ConsumerRecord<String, ChatMessage> record, Acknowledgment ack) {
        ChatMessage event = record.value();
        String roomId = event != null && event.getRoomId() != null ? event.getRoomId() : record.key();

        try {
            if (event == null) {
                log.warn("Kafka 이벤트 payload가 비어 있습니다. Offset: {}", record.offset());
                ack.acknowledge();
                return;
            }

            log.info("Kafka 이벤트 수신 - Type: {}, RoomId: {}, Sender: {}",
                    event.getType(), roomId, event.getSender());

            getChatRoom(roomId);
            if (event.getType() == null) {
                throw new IllegalArgumentException("이벤트 타입이 비어 있습니다.");
            }

            // 이벤트 타입에 따른 처리
            switch (event.getType()) {
                case JOIN:
                    handleJoinEvent(roomId, event);
                    break;
                case LEAVE:
                    handleLeaveEvent(roomId, event);
                    break;
                case SYSTEM:
                    handleSystemEvent(roomId, event);
                    break;
                default:
                    log.warn("알 수 없는 이벤트 타입: {}", event.getType());
            }

            // WebSocket으로 이벤트 브로드캐스트
            String destination = "/topic/chat/" + roomId;
            messagingTemplate.convertAndSend(destination, event);

            ack.acknowledge();

        } catch (IllegalArgumentException e) {
            log.warn("잘못된 Kafka 이벤트 폐기 - Type: {}, RoomId: {}, Error: {}",
                    event != null ? event.getType() : null, roomId, e.getMessage());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("이벤트 처리 실패 - Type: {}, RoomId: {}, Error: {}",
                    event.getType(), roomId, e.getMessage(), e);
        }
    }

    private void updateChatRoomLastMessage(ChatRoom room, ChatMessage message) {
        room.updateLastMessage(message.getContent(), message.getTimestamp());
        chatRoomRepository.save(room);
    }

    private void incrementUnreadCount(ChatRoom room, User sender) {
        userChatRoomRepository.incrementUnreadCountForAllUsersExceptSender(room, sender);
    }

    private ChatRoom getChatRoom(String roomId) {
        Long roomIdLong = parseId(roomId, "roomId");
        return chatRoomRepository.findById(roomIdLong)
                .orElseThrow(() -> new IllegalArgumentException("채팅방을 찾을 수 없습니다: " + roomId));
    }

    private User getSender(String senderId) {
        Long senderIdLong = parseId(senderId, "senderId");
        return userRepository.findById(senderIdLong)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + senderId));
    }

    private Long parseId(String value, String fieldName) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " 형식이 올바르지 않습니다: " + value);
        }
    }

    private void handleJoinEvent(String roomId, ChatMessage event) {
        log.info("사용자 입장 - RoomId: {}, User: {}", roomId, event.getSenderName());
        // 추가 로직 (예: 입장 알림 저장)
    }

    private void handleLeaveEvent(String roomId, ChatMessage event) {
        log.info("사용자 퇴장 - RoomId: {}, User: {}", roomId, event.getSenderName());
        // 추가 로직 (예: 퇴장 알림 저장)
    }

    private void handleSystemEvent(String roomId, ChatMessage event) {
        log.info("시스템 메시지 - RoomId: {}, Content: {}", roomId, event.getContent());
    }
}
