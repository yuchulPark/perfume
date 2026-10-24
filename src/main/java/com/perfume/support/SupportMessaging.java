package com.perfume.support;

import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
@ConditionalOnProperty(name = "perfume.support.enabled", havingValue = "true")
public class SupportMessaging {
    private final SupportStore store;
    private final SupportConnections sockets;
    private final SimpMessagingTemplate template;
    public SupportMessaging(SupportStore store, SupportConnections sockets, SimpMessagingTemplate template) { this.store = store; this.sockets = sockets; this.template = template; }
    @MessageMapping("/support/visitor.send") public void visitor(SupportModels.Send request, SimpMessageHeaderAccessor headers) { send(request, headers, false); }
    @MessageMapping("/support/admin.send") public void admin(SupportModels.Send request, SimpMessageHeaderAccessor headers) { send(request, headers, true); }
    @org.springframework.messaging.handler.annotation.MessageExceptionHandler(Exception.class)
    @org.springframework.messaging.simp.annotation.SendToUser(value = "/queue/support.errors", broadcast = false)
    public SupportModels.Failure malformed() { return new SupportModels.Failure("SUPPORT_INVALID_MESSAGE", null); }
    private void send(SupportModels.Send request, SimpMessageHeaderAccessor headers, boolean admin) {
        var session = (HttpSession) headers.getSessionAttributes().get(SupportSessions.HTTP);
        String route = SupportSessions.route(session);
        try {
            if (admin) SupportSessions.requireAdmin(session);
            var event = store.send(request, SupportSessions.visitor(session), admin);
            sockets.publish(event, store.owner(event.conversation().id()));
        } catch (SupportFailure failure) { template.convertAndSendToUser(route, "/queue/support.errors", new SupportModels.Failure(failure.code(), request == null ? null : request.clientMessageId())); }
        catch (org.springframework.dao.DataAccessException failure) { template.convertAndSendToUser(route, "/queue/support.errors", new SupportModels.Failure("SUPPORT_UNAVAILABLE", request == null ? null : request.clientMessageId())); }
    }
}
