package com.perfume.support;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.http.HttpSession;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** Bounded live transport registry, not a replacement for HttpSession authorization. */
public final class SupportConnections {
    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messages;
    public SupportConnections(SimpMessagingTemplate messages) { this.messages = messages; }
    synchronized void opened(WebSocketSession socket) throws java.io.IOException {
        var http = http(socket);
        long same = sockets.values().stream().filter(s -> http(s) == http).count();
        if (!SupportSessions.alive(http) || sockets.size() >= 256 || same >= 3) {
            socket.close(CloseStatus.POLICY_VIOLATION); return;
        }
        sockets.put(socket.getId(), socket);
    }
    void closed(String id) { sockets.remove(id); }
    void reject(String id) {
        if (id == null) return;
        var socket = sockets.get(id);
        if (socket != null) try { socket.close(new CloseStatus(1008, "SUPPORT_DENIED")); } catch (java.io.IOException ignored) { }
    }
    static HttpSession http(WebSocketSession socket) { return (HttpSession) socket.getAttributes().get(SupportSessions.HTTP); }
    boolean live(String id, String destination) {
        if (id == null) return false;
        var socket = sockets.get(id);
        return socket != null && SupportSessions.alive(http(socket))
            && (destination == null || !destination.startsWith("/queue/support.admin") || SupportSessions.admin(http(socket)));
    }
    void reap() {
        for (var socket : sockets.values()) if (!SupportSessions.alive(http(socket))) {
            sockets.remove(socket.getId());
            try { socket.close(CloseStatus.POLICY_VIOLATION); } catch (java.io.IOException ignored) { }
        }
    }
    public void publish(SupportModels.Event event, UUID owner) {
        // Committed events only. Current session state is rechecked even on an already-subscribed socket.
        var delivered = new java.util.HashSet<String>();
        for (var socket : sockets.values()) {
            HttpSession session = http(socket);
            if (!SupportSessions.alive(session)) continue;
            try {
                String route = SupportSessions.route(session);
                if (!delivered.add(route)) continue;
                if (SupportSessions.visitor(session).equals(owner)) messages.convertAndSendToUser(route, "/queue/support.events", event);
                if (SupportSessions.admin(session)) messages.convertAndSendToUser(route, "/queue/support.admin", event);
            } catch (IllegalStateException | SupportFailure expired) { /* Session expired between checks. */ }
        }
    }
}
