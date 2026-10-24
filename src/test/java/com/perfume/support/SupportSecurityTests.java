package com.perfume.support;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;

class SupportSecurityTests {
    MockHttpSession browser(boolean admin) {
        var session = new MockHttpSession();
        if (admin) session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated("admin", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"))));
        return session;
    }
    void check(MockHttpSession session, StompCommand command, String destination) {
        var h = StompHeaderAccessor.create(command); h.setSessionAttributes(session == null ? Map.of() : new java.util.HashMap<>(Map.of(SupportSessions.HTTP, session)));
        if (command == StompCommand.SUBSCRIBE) h.setSubscriptionId("test-subscription");
        if (destination != null) h.setDestination(destination);
        new SupportSocketConfiguration.Authorization().preSend(MessageBuilder.createMessage(new byte[0], h.getMessageHeaders()), null);
    }
    @Test void onlyExactPersonalDestinationsAndServerRolesAreAuthorized() {
        var guest = browser(false); var admin = browser(true);
        check(guest, StompCommand.CONNECT, null); check(guest, StompCommand.SUBSCRIBE, "/user/queue/support.events");
        check(guest, StompCommand.SEND, "/app/support/visitor.send");
        check(admin, StompCommand.SUBSCRIBE, "/user/queue/support.admin"); check(admin, StompCommand.SEND, "/app/support/admin.send");
        for (String path : new String[]{"/user/queue/support.admin","/topic/support/other","/queue/support.events-userOther","/user/other/queue/support.events","/user/queue/support.events/extra"})
            assertThrows(AccessDeniedException.class, () -> check(guest, StompCommand.SUBSCRIBE, path));
        for (String path : new String[]{"/app/support/admin.send","/queue/support.events","/topic/support/test","/app/support/visitor.send/extra"})
            assertThrows(AccessDeniedException.class, () -> check(guest, StompCommand.SEND, path));
    }
    @Test void logoutAndExpirationRevokePreviouslyGrantedStompRights() {
        var session = browser(true); check(session, StompCommand.SEND, "/app/support/admin.send");
        session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThrows(AccessDeniedException.class, () -> check(session, StompCommand.SEND, "/app/support/admin.send"));
        assertThrows(AccessDeniedException.class, () -> check(session, StompCommand.SUBSCRIBE, "/user/queue/support.admin"));
        session.invalidate(); assertFalse(SupportSessions.alive(session));
        assertThrows(AccessDeniedException.class, () -> check(session, StompCommand.SEND, "/app/support/visitor.send"));
    }
    @Test void visitorIdentitySurvivesLoginSessionRotationAndIsNeverSharedAcrossSessions() {
        var a = browser(false); var b = browser(false);
        var key = SupportSessions.visitor(a); a.changeSessionId();
        assertEquals(key, SupportSessions.visitor(a)); assertNotEquals(key, SupportSessions.visitor(b));
        assertNotEquals(SupportSessions.route(a), SupportSessions.route(b));
    }
    @Test void invalidOriginConfigurationsAndUnsafeActivationAreRejected() {
        for (String origin : new String[]{"", "*", "http://*.example.com", "http://localhost/", "http://user@localhost", "http://localhost?x=a", "ws://localhost"})
            assertThrows(IllegalStateException.class, () -> SupportConfiguration.origins(new MockEnvironment().withProperty("perfume.support.allowed-origins", origin)));
        assertEquals("http://127.0.0.1:18081", SupportConfiguration.origins(new MockEnvironment().withProperty("perfume.support.allowed-origins", "http://127.0.0.1:18081"))[0]);
        var guard = new SupportConfiguration().supportActivationGuard(null, new MockEnvironment());
        assertThrows(IllegalStateException.class, guard::afterSingletonsInstantiated);
    }
    @Test void excessiveFramesCannotGrowUnboundedServerState() {
        var session = browser(false);
        for (int i=0;i<120;i++) SupportAdmission.frame(session);
        assertThrows(AccessDeniedException.class, () -> SupportAdmission.frame(session));
    }
    @Test void outgoingAdminEventsAreBlockedImmediatelyAfterRoleRemovalOrLogout() throws Exception {
        var template = org.mockito.Mockito.mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);
        var registry = new SupportConnections(template);
        var admin = browser(true);
        var socket = org.mockito.Mockito.mock(org.springframework.web.socket.WebSocketSession.class);
        org.mockito.Mockito.when(socket.getId()).thenReturn("test-socket");
        org.mockito.Mockito.when(socket.getAttributes()).thenReturn(Map.of(SupportSessions.HTTP, admin));
        registry.opened(socket);
        assertTrue(registry.live("test-socket", "/queue/support.admin-userTest"));
        admin.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertFalse(registry.live("test-socket", "/queue/support.admin-userTest"));
        assertTrue(registry.live("test-socket", "/queue/support.events-userTest"));
        admin.invalidate(); assertFalse(registry.live("test-socket", "/queue/support.events-userTest"));
        registry.reap(); org.mockito.Mockito.verify(socket).close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
    }
    @Test void inactiveHttpSessionsCannotBeKeptAliveBySendingWebsocketFrames() {
        var session = org.mockito.Mockito.mock(jakarta.servlet.http.HttpSession.class);
        org.mockito.Mockito.when(session.getMaxInactiveInterval()).thenReturn(1);
        org.mockito.Mockito.when(session.getLastAccessedTime()).thenReturn(System.currentTimeMillis()-5000);
        assertFalse(SupportSessions.alive(session));
        assertThrows(AccessDeniedException.class, () -> check(null, StompCommand.SEND, "/app/support/admin.send"));
    }
}
