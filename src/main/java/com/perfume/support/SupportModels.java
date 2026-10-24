package com.perfume.support;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SupportModels {
    private SupportModels() { }
    public record Conversation(UUID id, String status, Instant createdAt, Instant updatedAt,
            Instant closedAt, Instant lastMessageAt, String lastMessage, long nextSequence, long unreadCount) { }
    public record ChatMessage(UUID id, UUID conversationId, long sequence, UUID clientMessageId,
            String senderType, String text, Instant createdAt) { }
    public record History(Conversation conversation, List<ChatMessage> messages, long nextAfter, boolean hasMore) { }
    public record Rooms(List<Conversation> conversations, int nextOffset, boolean hasMore) { }
    public record Send(UUID conversationId, UUID clientMessageId, String text) { }
    public record Read(long sequence) { }
    public record Event(String type, Conversation conversation, ChatMessage message) { }
    public record Failure(String code, UUID clientMessageId) { }
}
