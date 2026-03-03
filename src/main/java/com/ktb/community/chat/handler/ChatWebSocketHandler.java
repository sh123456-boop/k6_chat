package com.ktb.community.chat.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.community.chat.dto.ChatMessagePubSubDto;
import com.ktb.community.chat.dto.ChatMessageReqDto;
import com.ktb.community.chat.mapper.DtoMapper;
import com.ktb.community.chat.service.ChatServiceImpl;
import com.ktb.community.chat.service.RedisPubSubService;
import com.ktb.community.chat.service.SessionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

/**
 * MVC WebSocket 핸들러: subscribe/unsubscribe/chat 메시지를 처리한다.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final SessionRegistry sessionRegistry;
    private final RedisPubSubService redisPubSubService;
    private final ChatServiceImpl chatService;
    private final DtoMapper dtoMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatWebSocketHandler(SessionRegistry sessionRegistry,
                                RedisPubSubService redisPubSubService,
                                ChatServiceImpl chatService,
                                DtoMapper dtoMapper) {
        this.sessionRegistry = sessionRegistry;
        this.redisPubSubService = redisPubSubService;
        this.chatService = chatService;
        this.dtoMapper = dtoMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionRegistry.registerSession(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        handleInbound(session.getId(), message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionRegistry.removeSession(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        sessionRegistry.removeSession(session.getId());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private void handleInbound(String sessionId, String payload) {
        try {
            Map<?, ?> map = objectMapper.readValue(payload, Map.class);
            String type = (String) map.get("type");
            Long roomId = asLong(map.get("roomId"));

            if ("subscribe".equalsIgnoreCase(type) && roomId != null) {
                sessionRegistry.subscribe(sessionId, roomId);
                return;
            }

            if ("unsubscribe".equalsIgnoreCase(type) && roomId != null) {
                sessionRegistry.unsubscribe(sessionId, roomId);
                return;
            }

            if ("chat".equalsIgnoreCase(type)) {
                ChatMessageReqDto req = ChatMessageReqDto.builder()
                        .roomId(roomId)
                        .senderId(asLong(map.get("senderId")))
                        .message((String) map.get("message"))
                        .build();

                if (req.getRoomId() == null || req.getSenderId() == null || req.getMessage() == null) {
                    return;
                }

                chatService.saveMessage(req.getRoomId(), req).block();
                ChatMessagePubSubDto dto = dtoMapper.toPubSubDto(req).block();
                if (dto == null) {
                    return;
                }

                String pubSubMessage = objectMapper.writeValueAsString(dto);
                redisPubSubService.publish("chat", pubSubMessage);
            }
        } catch (Exception e) {
            // ignore malformed payload
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return value != null ? Long.parseLong(value.toString()) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
