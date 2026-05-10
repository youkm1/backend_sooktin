package com.sooktin.backend.websocket;

import com.sooktin.backend.domain.ChatMessage;
import com.sooktin.backend.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {
    private final ChatService chatService;

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return;
        }

        String nickname = (String) sessionAttributes.get("nickname");
        String sender = (String) sessionAttributes.get("sender");
        String roomId = (String) sessionAttributes.get("roomId");

        if (nickname != null && roomId != null) {
            log.info("User Disconnected : {}", nickname);

            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setSender(sender != null ? sender : nickname);
            chatMessage.setSenderName(nickname); //필요시 표시이름설정
            chatMessage.setType(ChatMessage.MessageType.LEAVE);

            chatService.handleDisconnect(roomId, chatMessage);
        }

    }
}
