package com.ktb.community.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisPubSubService {

    private final StringRedisTemplate stringRedisTemplate;
    private final SessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisPubSubService(@Qualifier("chatPubSub") StringRedisTemplate stringRedisTemplate,
                              SessionRegistry sessionRegistry) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.sessionRegistry = sessionRegistry;
    }

    public void publish(String channel, String message) {
        stringRedisTemplate.convertAndSend(channel, message);
    }

    public void handleMessage(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            Long roomId = node.path("roomId").isNumber() ? node.path("roomId").asLong() : null;
            if (roomId != null) {
                sessionRegistry.broadcast(roomId, payload);
            }
        } catch (Exception e) {
            // ignore malformed payload to avoid crashing listener
        }
    }
}
