package com.sooktin.backend.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.sooktin.backend.domain.ChatRoom;
import com.sooktin.backend.domain.User;
import com.sooktin.backend.domain.UserChatRoom;
import com.sooktin.backend.dto.chat.ChatRoomSummaryDTO;
import com.sooktin.backend.kafka.KafkaChatProducer;
import com.sooktin.backend.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import com.sooktin.backend.domain.ChatMessage.MessageType;
import com.sooktin.backend.domain.ChatMessage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class ChatService {
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatRepository chatRepository;
    private final UserChatRoomRepository userChatRoomRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final KafkaChatProducer kafkaChatProducer;  // Kafka Producer (optional)

    @Autowired
    public ChatService(
            SimpMessagingTemplate messagingTemplate,
            ChatRepository chatRepository,
            UserChatRoomRepository userChatRoomRepository,
            ChatRoomRepository chatRoomRepository,
            UserRepository userRepository,
            @Autowired(required = false) KafkaChatProducer kafkaChatProducer
    ) {
        this.messagingTemplate = messagingTemplate;
        this.chatRepository = chatRepository;
        this.userChatRoomRepository = userChatRoomRepository;
        this.chatRoomRepository = chatRoomRepository;
        this.userRepository = userRepository;
        this.kafkaChatProducer = kafkaChatProducer;
    }
    
    @Transactional(readOnly = true)
    public List<ChatRoomSummaryDTO> getUserChatRooms(Long userId) {
        User currentUser = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        List<UserChatRoom> userChatRooms = userChatRoomRepository.findByUser(currentUser);

        return userChatRooms.stream()
                .map(ucr->convertToChatRoomSummary(ucr,userId))
                .collect(Collectors.toList());
    }

    private ChatRoomSummaryDTO convertToChatRoomSummary(UserChatRoom ucr, Long userId) {
        ChatRoom room = ucr.getRoom();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm a", Locale.ENGLISH);

        String formattedCreatedAt = room.getCreatedAt() != null ?
                room.getCreatedAt().format(formatter) : null;

        String formattedLastMessageAt = room.getLastMessageAt() != null ?
                room.getLastMessageAt().format(formatter) : null;

        ChatRoomSummaryDTO.ChatRoomSummaryDTOBuilder builder = ChatRoomSummaryDTO.builder()
                .roomId(room.getId())
                .lastMessage(room.getLastMessagePreview())
                .lastMessageAt(formattedLastMessageAt)
                .createdAt(formattedCreatedAt)
                .unreadCount(ucr.getUnreadCount());

        try {
            // 상대방 정보 가져오기 시도
            User opponentUser = findOtherUser(room, userId);

            if (opponentUser != null) {
                // 상대방 정보 추가
                builder.opponentUserNickname(opponentUser.getNickname())
                        .opponentCareerCardId(opponentUser.getCareerCard() != null ? opponentUser.getCareerCard().getId() : null)
                        .opponentUserId(opponentUser.getId());

                // 프로필 이미지 안전하게 가져오기
                try {
                    if (opponentUser.getCareerCard() != null &&
                            opponentUser.getCareerCard().getImageUrls() != null &&
                            !opponentUser.getCareerCard().getImageUrls().isEmpty()) {
                        builder.opponentImageUrl(opponentUser.getCareerCard().getImageUrls().get(0));
                    }
                } catch (Exception e) {
                    log.warn("프로필 이미지를 가져오는 데 실패했습니다. 사용자 ID: {}", opponentUser.getId(), e);
                }
            } else {
                // 상대방이 없는 경우 기본값 설정
                builder.opponentUserNickname("알 수 없는 사용자")
                        .opponentUserId(0L);
                log.warn("채팅방 ID {}에 상대방이 없습니다.", room.getId());
            }
        } catch (Exception e) {
            // 예외 발생 시 기본값 설정
            builder.opponentUserNickname("알 수 없는 사용자")
                    .opponentUserId(0L);
            log.error("채팅방 ID {}의 상대방 정보를 가져오는 데 실패했습니다: {}", room.getId(), e.getMessage());
        }

        return builder.build();
    }

    private User findOtherUser(ChatRoom room, Long currentUserId) {
        if (room == null) {
            throw new IllegalArgumentException("채팅방이 null입니다.");
        }

        return userChatRoomRepository.findByRoom(room).stream()
                .map(UserChatRoom::getUser)
                .filter(user -> user!=null && !user.getId().equals(currentUserId))
                .findFirst()
                .orElse(null);
    }

    private String getProfileImage(User user) {
        try {
            if (user != null && user.getCareerCard() != null &&
                    user.getCareerCard().getImageUrls() != null &&
                    !user.getCareerCard().getImageUrls().isEmpty()) {
                return user.getCareerCard().getImageUrls().get(0);
            }
        } catch (Exception e) {
            log.warn("사용자 ID {}의 프로필 이미지를 가져오는 데 실패했습니다: {}",
                    user != null ? user.getId() : "null", e.getMessage());
        }
        // 기본 이미지 URL 또는 null 반환
        return null;
    }
    
    // 추가: AMQP로도 전송 (Consumer용, 라우팅키 필요)
            /*if (rabbitTemplate != null) {
                rabbitTemplate.convertAndSend(CHAT_EXCHANGE, "room." + roomId, savedMessage);
                log.info("AMQP: Exchange={}, RoutingKey=room.{}", CHAT_EXCHANGE, roomId);
            }*/

    @Transactional
    public void sendMessage(String roomId, ChatMessage message) {
        try {
            validateChatMessagePayload(message);
            ChatRoom room = getChatRoomOrThrow(roomId);
            User sender = resolveUserIdentifier(message.getSender());
            UserChatRoom senderRoom = userChatRoomRepository.findByUserAndRoom(sender, room)
                    .orElseThrow(() -> new IllegalArgumentException("사용자가 채팅방의 멤버가 아닙니다."));

            message.setRoomId(roomId);
            message.setType(MessageType.CHAT);
            message.setSender(sender.getId().toString());
            if (isBlank(message.getSenderName())) {
                message.setSenderName(sender.getNickname());
            }
            if (message.getTimestamp() == null) {
                message.setTimestamp(LocalDateTime.now());
            }

            // Kafka 사용 시 (local 프로파일)
            if (kafkaChatProducer != null) {
                // Kafka로 메시지 전송 (Consumer에서 DB 저장 + WebSocket 브로드캐스트)
                kafkaChatProducer.sendMessage(message);
                log.info("Kafka Producer: chat.messages, RoomId: {}", roomId);
                return;
            }

            // 기존 로직 (RabbitMQ / In-Memory)
            ChatMessage savedMessage = chatRepository.save(message);
            updateRoomLastMessage(room, savedMessage);
            incrementUnreadCount(room, sender);
            senderRoom.markAsRead(savedMessage.getMessageId());

            broadcastToRoom(roomId, savedMessage);

        } catch (Exception e) {
            log.error("Error in sendMessage: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public void markMessageAsRead(String roomId, String userIdentifier) {
        ChatRoom room = getChatRoomOrThrow(roomId);
        User user = resolveUserIdentifier(userIdentifier);
        UserChatRoom userChatRoom = userChatRoomRepository.findByUserAndRoom(user, room)
                .orElseThrow(() -> new IllegalArgumentException("사용자가 채팅방의 멤버가 아닙니다."));

        Long latestMessageId = chatRepository.findTopByRoomIdOrderByTimestampDesc(roomId)
                .map(ChatMessage::getMessageId)
                .orElse(null);
        userChatRoom.markAsRead(latestMessageId);
        userChatRoomRepository.save(userChatRoom);
    }

    public void notifyUserViewing(String roomId, ChatMessage message) {
        String displayName = getDisplayName(message);
        ChatMessage viewingMessage = new ChatMessage();
        viewingMessage.setRoomId(roomId);
        viewingMessage.setType(MessageType.SYSTEM);
        viewingMessage.setSender(message.getSender());
        viewingMessage.setSenderName(displayName);
        viewingMessage.setContent(displayName + "님이 채팅방을 보고 있습니다.");

        // Kafka 사용 시
        if (kafkaChatProducer != null) {
            kafkaChatProducer.sendEvent(viewingMessage);
            return;
        }

        broadcastToRoom(roomId, viewingMessage);
    }

    // 사용자가 채팅 화면 보기를 종료
    public void notifyUserExitedView(String roomId, ChatMessage message) {
        String displayName = getDisplayName(message);
        ChatMessage exitViewMessage = new ChatMessage();
        exitViewMessage.setRoomId(roomId);
        exitViewMessage.setType(MessageType.SYSTEM);
        exitViewMessage.setSender(message.getSender());
        exitViewMessage.setSenderName(displayName);
        exitViewMessage.setContent(displayName + "님이 채팅방을 나갔습니다.");

        // Kafka 사용 시
        if (kafkaChatProducer != null) {
            kafkaChatProducer.sendEvent(exitViewMessage);
            return;
        }

        broadcastToRoom(roomId, exitViewMessage);
    }

    //사용자가 완전 채팅방 나갔을 때 (멤버십 제거)
    @Transactional
    public void handleLeaveRoom(String roomId, ChatMessage message) {
        message.setType(MessageType.LEAVE);
        message.setRoomId(roomId);

        // 메시지 내용이 없으면 기본 메시지 설정
        if (isBlank(message.getContent())) {
            message.setContent(getDisplayName(message) + "님이 채팅방을 나갔습니다.");
        }

        // Kafka 사용 시
        if (kafkaChatProducer != null) {
            kafkaChatProducer.sendEvent(message);
            return;
        }

        // 메시지 저장
        ChatMessage savedMessage = chatRepository.save(message);

        broadcastToRoom(roomId, savedMessage);
    }
    // WebSocket 연결이 끊겼을 때 호출되는 메서드
    public void handleDisconnect(String roomId, ChatMessage message) {
        // 연결 끊김 처리 (일시적 연결 끊김으로 간주)
        message.setType(MessageType.LEAVE);
        message.setRoomId(roomId);
        message.setContent(getDisplayName(message) + "님의 연결이 끊겼습니다.");

        if (kafkaChatProducer != null) {
            kafkaChatProducer.sendEvent(message);
            return;
        }

        // 메시지 저장
        ChatMessage savedMessage = chatRepository.save(message);

        broadcastToRoom(roomId, savedMessage);
    }

    public List<ChatMessage> getLatestMessages(String roomId, int limit) {
        return chatRepository.findLatestMessages(roomId, limit);
    }

    private void validateChatMessagePayload(ChatMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("메시지 payload가 비어 있습니다.");
        }
        if (isBlank(message.getSender())) {
            throw new IllegalArgumentException("메시지 발신자 정보가 필요합니다.");
        }
        if (isBlank(message.getContent())) {
            throw new IllegalArgumentException("메시지 내용이 비어 있습니다.");
        }
    }

    private ChatRoom getChatRoomOrThrow(String roomId) {
        Long parsedRoomId = parseRoomId(roomId);
        return chatRoomRepository.findById(parsedRoomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방을 찾을 수 없습니다: " + roomId));
    }

    private Long parseRoomId(String roomId) {
        try {
            return Long.parseLong(roomId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("채팅방 ID 형식이 올바르지 않습니다: " + roomId);
        }
    }

    private User resolveUserIdentifier(String identifier) {
        if (isBlank(identifier)) {
            throw new IllegalArgumentException("사용자 식별자가 비어 있습니다.");
        }

        try {
            Long userId = Long.parseLong(identifier);
            return userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + identifier));
        } catch (NumberFormatException ignored) {
            return userRepository.findByEmail(identifier)
                    .or(() -> userRepository.findByNickname(identifier))
                    .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + identifier));
        }
    }

    private void updateRoomLastMessage(ChatRoom room, ChatMessage message) {
        room.updateLastMessage(message.getContent(), message.getTimestamp());
        chatRoomRepository.save(room);
    }

    private void incrementUnreadCount(ChatRoom room, User sender) {
        userChatRoomRepository.incrementUnreadCountForAllUsersExceptSender(room, sender);
    }

    private void broadcastToRoom(String roomId, ChatMessage message) {
        String destination = "/topic/chat/" + roomId;
        messagingTemplate.convertAndSend(destination, message);
        log.info("WebSocket broadcast: {}", destination);
    }

    private String getDisplayName(ChatMessage message) {
        if (message == null) {
            return "알 수 없는 사용자";
        }
        if (!isBlank(message.getSenderName())) {
            return message.getSenderName();
        }
        if (!isBlank(message.getSender())) {
            try {
                return resolveUserIdentifier(message.getSender()).getNickname();
            } catch (IllegalArgumentException ignored) {
                return message.getSender();
            }
        }
        return "알 수 없는 사용자";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
