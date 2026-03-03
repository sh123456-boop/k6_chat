package com.ktb.community.chat.config;

import com.ktb.community.chat.service.ChatServiceImpl;
import com.ktb.community.util.JWTUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class ChatWebSocketAuthFilter implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketAuthFilter.class);

    private final JWTUtil jwtUtil;
    private final ChatServiceImpl chatService;

    public ChatWebSocketAuthFilter(JWTUtil jwtUtil, ChatServiceImpl chatService) {
        this.jwtUtil = jwtUtil;
        this.chatService = chatService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        log.debug("WebSocket auth interceptor hit: {}", path);

        String access = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("access");
        String roomIdParam = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("roomId");

        if (access == null) {
            return unauthorized(response, "missing access token");
        }
        if (roomIdParam == null) {
            return unauthorized(response, "missing roomId");
        }

        Long userId;
        Long roomId;

        try {
            if (jwtUtil.isExpired(access)) {
                return unauthorized(response, "access token expired");
            }

            userId = jwtUtil.getID(access);
            roomId = Long.parseLong(roomIdParam);
        } catch (Exception e) {
            return unauthorized(response, "invalid token");
        }

        try {
            boolean isParticipant = Boolean.TRUE.equals(chatService.isRoomParticipant(userId, roomId).block());
            if (!isParticipant) {
                return forbidden(response, "access denied for room userId=" + userId + " roomId=" + roomId);
            }
        } catch (Exception e) {
            return unauthorized(response, "invalid token");
        }

        attributes.put("userId", userId);
        attributes.put("roomId", roomId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
    }

    private boolean unauthorized(ServerHttpResponse response, String message) {
        log.warn("WebSocket auth unauthorized: {}", message);
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    private boolean forbidden(ServerHttpResponse response, String message) {
        log.warn("WebSocket auth forbidden: {}", message);
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }
}
