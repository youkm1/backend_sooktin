package com.sooktin.backend.controller;

import com.sooktin.backend.domain.ChatMessage;
import com.sooktin.backend.domain.ChatRoom;
import com.sooktin.backend.domain.User;
import com.sooktin.backend.domain.UserChatRoom;
import com.sooktin.backend.dto.ResponseDto;
import com.sooktin.backend.dto.chat.ChatRoomSummaryDTO;
import com.sooktin.backend.dto.chat.ChatRoomStatusUpdateRequest;
import com.sooktin.backend.dto.chat.UserChatRoomDTO;
import com.sooktin.backend.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatHttpController {
    private final ChatRoomService chatRoomService;
    private final ChatService chatService;
    private final UserChatRoomService userChatRoomService;
    private final UserService userService;

    @GetMapping("/rooms")
    public ResponseEntity<ResponseDto<List<ChatRoomSummaryDTO>>> getUserChatRooms(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        log.info("채팅방 목록 조회 요청. 사용자 ID: {}", userDetails.getUserId());

        List<ChatRoomSummaryDTO> chatRooms = chatService.getUserChatRooms(userDetails.getUserId());

        log.info("채팅방 목록 조회 완료. 사용자 ID: {}, 채팅방 수: {}",
                userDetails.getUserId(), chatRooms.size());

        return ResponseEntity.ok(new ResponseDto<>(
                200,
                "채팅방 목록을 성공적으로 가져왔습니다.",
                chatRooms
        ));
    }

    @PostMapping("/rooms")
    public ResponseEntity<ResponseDto<ChatRoom>> createChatRoom(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @RequestParam Long targetUserId
    ) {
        if (userDetails.getUserId().equals(targetUserId)) {
            throw new IllegalArgumentException("자기 자신과는 채팅방을 생성할 수 없습니다.");
        }
        userService.findUserById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("대상 사용자를 찾을 수 없습니다: " + targetUserId));

        ChatRoom chatRoom = new ChatRoom();

        /*if (name != null && !name.trim().isEmpty()) {
            chatRoom.setName(name);

        } else {
            chatRoom.setDirectMessage(true);
        }*/

        ChatRoom savedChatRoom = chatRoomService.createChatRoom(chatRoom);

        userChatRoomService.addUserToRoom(userDetails.getUserId(), savedChatRoom.getId());
        userChatRoomService.addUserToRoom(targetUserId, savedChatRoom.getId());

        return ResponseEntity.ok(new ResponseDto<>(201, "채팅방이 성공적으로 생성되었습니다.", savedChatRoom));
    }

    @DeleteMapping("/rooms/{roomId}/leave")
    public ResponseEntity<ResponseDto<Void>> leaveChatRoom(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long roomId
    ) {

        if (!userChatRoomService.isMember(userDetails.getUserId(), roomId)) {
            throw new IllegalArgumentException("사용자가 채팅방의 멤버가 아닙니다.");
        }

        ChatMessage leaveMessage = new ChatMessage();
        leaveMessage.setRoomId(roomId.toString());
        leaveMessage.setSender(userDetails.getUserId().toString());
        leaveMessage.setSenderName(userDetails.getNickname());
        leaveMessage.setContent(userDetails.getNickname() + "님이 채팅방을 나갔습니다.");
        leaveMessage.setType(ChatMessage.MessageType.LEAVE);

        chatService.handleLeaveRoom(roomId.toString(), leaveMessage);

        userChatRoomService.removeUserFromRoom(userDetails.getUserId(), roomId);

        return ResponseEntity.ok(new ResponseDto<>(200, "채팅방을 나갔습니다.", null));
    }
    //사용자의 모든 채팅방 조회
    @GetMapping("/{userId}")
    public ResponseEntity<ResponseDto<List<UserChatRoomDTO>>> getUserChatRoomRelations(
            @PathVariable Long userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        // 자신의 정보만 조회 가능하도록 검증
        if (!userDetails.getUserId().equals(userId)) {
            throw new IllegalArgumentException("다른 사용자의 채팅방 정보를 조회할 권한이 없습니다.");
        }

        List<UserChatRoom> chatRoomRelations = userChatRoomService.getUserChatRoomRelations(userId);

        List<UserChatRoomDTO> userChatRoomDTOS = chatRoomRelations.stream()
                .map(UserChatRoomDTO::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(new ResponseDto<>(200, "사용자의 채팅방 관계 정보를 성공적으로 조회했습니다.", userChatRoomDTOS));
    }

    @PatchMapping("/rooms/{roomId}")
    public ResponseEntity<ResponseDto<Void>> updateRoomStatus(
            @PathVariable Long roomId,
            @RequestBody ChatRoomStatusUpdateRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        // 사용자가 채팅방의 구성원인지 확인
        if (!userChatRoomService.isMember(userDetails.getUserId(), roomId)) {
            throw new IllegalArgumentException("해당 채팅방의 구성원이 아닙니다.");
        }

        // 채팅방 상태 업데이트
        userChatRoomService.updateRoomStatus(userDetails.getUserId(), roomId, request);

        return ResponseEntity.ok(new ResponseDto<>(200, "채팅방 상태가 성공적으로 업데이트되었습니다.", null));
    }

    @PostMapping("/rooms/{roomId}/enter")
    public ResponseEntity<ResponseDto<Map<String, Object>>> enterChatRoom(
            @PathVariable Long roomId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        // 사용자가 채팅방의 구성원인지 확인
        if (!userChatRoomService.isMember(userDetails.getUserId(), roomId)) {
            throw new IllegalArgumentException("해당 채팅방의 구성원이 아닙니다.");
        }

        // 채팅방 정보 조회
        ChatRoom chatRoom = chatRoomService.getChatRoomById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방을 찾을 수 없습니다."));

        // 채팅방 참여자 목록 조회
        Set<User> participants = chatRoomService.getParticipants(roomId);

        // 최근 메시지 조회 (최대 50개)
        List<ChatMessage> recentMessages = chatService.getLatestMessages(roomId.toString(), 50);

        // 읽지 않은 메시지 수 조회
        int unreadCount = userChatRoomService.getUnreadCount(userDetails.getUserId(), roomId);

        // 읽음 처리
        if (!recentMessages.isEmpty()) {
            Long lastMessageId = recentMessages.get(0).getMessageId();
            userChatRoomService.markMessagesAsRead(userDetails.getUserId(), roomId, lastMessageId);
        }

        // Map을 사용하여 응답 데이터 구성
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("room", chatRoom);
        responseData.put("participants", participants);
        responseData.put("messages", recentMessages);
        responseData.put("unreadCount", unreadCount);

        return ResponseEntity.ok(new ResponseDto<>(200, "채팅방에 성공적으로 입장했습니다.", responseData));
    }

}
