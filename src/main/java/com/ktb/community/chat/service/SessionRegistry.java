package com.ktb.community.chat.service;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 세션별 구독 방 목록과 outbound 세션을 관리한다.
 * 인메모리라 단일 인스턴스/스티키 세션 가정.
 */
@Component
public class SessionRegistry {

    private static final int SEND_TIMEOUT_MILLIS = 10_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    public static class SessionConnection {
        private final String sessionId;
        private final Set<Long> roomIds;
        private final ConcurrentWebSocketSessionDecorator session;

        SessionConnection(String sessionId, Set<Long> roomIds, ConcurrentWebSocketSessionDecorator session) {
            this.sessionId = sessionId;
            this.roomIds = roomIds;
            this.session = session;
        }

        public String getSessionId() {
            return sessionId;
        }

        public Set<Long> getRoomIds() {
            return roomIds;
        }

        public ConcurrentWebSocketSessionDecorator getSession() {
            return session;
        }
    }

    private final Map<String, SessionConnection> sessions = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> roomSubscriptions = new ConcurrentHashMap<>();

    public SessionConnection registerSession(WebSocketSession session) {
        return sessions.computeIfAbsent(session.getId(), id -> {
            Set<Long> rooms = Collections.newSetFromMap(new ConcurrentHashMap<>());
            ConcurrentWebSocketSessionDecorator decoratedSession =
                    new ConcurrentWebSocketSessionDecorator(session, SEND_TIMEOUT_MILLIS, BUFFER_SIZE_LIMIT);
            return new SessionConnection(id, rooms, decoratedSession);
        });
    }

    public void removeSession(String sessionId) {
        SessionConnection connection = sessions.remove(sessionId);
        if (connection == null) {
            return;
        }

        connection.getRoomIds().forEach(roomId -> {
            Set<String> sessionIds = roomSubscriptions.get(roomId);
            if (sessionIds != null) {
                sessionIds.remove(sessionId);
                if (sessionIds.isEmpty()) {
                    roomSubscriptions.remove(roomId);
                }
            }
        });
    }

    public void subscribe(String sessionId, Long roomId) {
        SessionConnection connection = sessions.get(sessionId);
        if (connection == null) {
            return;
        }

        connection.getRoomIds().add(roomId);
        roomSubscriptions
                .computeIfAbsent(roomId, id -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(sessionId);
    }

    public void unsubscribe(String sessionId, Long roomId) {
        SessionConnection connection = sessions.get(sessionId);
        if (connection != null) {
            connection.getRoomIds().remove(roomId);
        }

        Set<String> sessionIds = roomSubscriptions.get(roomId);
        if (sessionIds != null) {
            sessionIds.remove(sessionId);
            if (sessionIds.isEmpty()) {
                roomSubscriptions.remove(roomId);
            }
        }
    }

    public void broadcast(Long roomId, String payload) {
        Set<String> sessionIds = roomSubscriptions.get(roomId);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }

        sessionIds.forEach(sessionId -> {
            SessionConnection connection = sessions.get(sessionId);
            if (connection == null || !connection.getSession().isOpen()) {
                removeSession(sessionId);
                return;
            }

            try {
                connection.getSession().sendMessage(new TextMessage(payload));
            } catch (IOException e) {
                removeSession(sessionId);
            }
        });
    }
}
