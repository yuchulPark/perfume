package com.perfume.support;

import java.security.Principal;
import java.util.Map;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.messaging.web.csrf.XorCsrfChannelInterceptor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnProperty(name = "perfume.support.enabled", havingValue = "true")
public class SupportSocketConfiguration implements WebSocketMessageBrokerConfigurer {
    private final Environment env;
    private final org.springframework.beans.factory.ObjectProvider<SupportConnections> connections;
    public SupportSocketConfiguration(Environment env, org.springframework.beans.factory.ObjectProvider<SupportConnections> connections) { this.env = env; this.connections = connections; }
    @Bean SupportConnections supportConnections(SimpMessagingTemplate template) { return new SupportConnections(template); }
    @Bean ThreadPoolTaskScheduler supportHeartbeat() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("support-heartbeat-"); return scheduler;
    }
    @Bean org.springframework.beans.factory.SmartInitializingSingleton supportSessionReaper(@Qualifier("supportHeartbeat") ThreadPoolTaskScheduler scheduler) {
        return () -> scheduler.scheduleWithFixedDelay(() -> connections.getObject().reap(), java.time.Duration.ofSeconds(5));
    }
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app"); registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/queue").setHeartbeatValue(new long[]{10000,10000}).setTaskScheduler(supportHeartbeat());
        registry.setPreservePublishOrder(true);
    }
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] allowed = SupportConfiguration.origins(env);
        registry.addEndpoint("/ws/support").setAllowedOrigins(allowed).addInterceptors(new HandshakeInterceptor() {
            @Override public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler handler, Map<String,Object> attrs) {
                if (!(req instanceof ServletServerHttpRequest servlet) || !java.util.Arrays.asList(allowed).contains(req.getHeaders().getOrigin())) {
                    res.setStatusCode(HttpStatus.FORBIDDEN); return false;
                }
                HttpSession session = servlet.getServletRequest().getSession(false);
                if (!SupportSessions.alive(session)) { res.setStatusCode(HttpStatus.FORBIDDEN); return false; }
                var raw = new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository().loadToken(servlet.getServletRequest());
                if (raw == null) { res.setStatusCode(HttpStatus.FORBIDDEN); return false; }
                attrs.put(SupportSessions.HTTP, session);
                attrs.put(SupportSessions.ROUTE, SupportSessions.route(session));
                attrs.put(CsrfToken.class.getName(), raw);
                return true;
            }
            @Override public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler handler, Exception ex) { }
        }).setHandshakeHandler(new DefaultHandshakeHandler() {
            @Override protected Principal determineUser(ServerHttpRequest req, WebSocketHandler handler, Map<String,Object> attrs) {
                String route = (String) attrs.get(SupportSessions.ROUTE); return () -> route;
            }
        });
        registry.setErrorHandler(new StompSubProtocolErrorHandler() {
            @Override public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> client, Throwable ex) {
                var h = StompHeaderAccessor.create(StompCommand.ERROR); h.setMessage("SUPPORT_DENIED");
                return MessageBuilder.createMessage(new byte[0], h.getMessageHeaders());
            }
        });
    }
    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(2).maxPoolSize(4).queueCapacity(256);
        registration.interceptors(new ChannelInterceptor() {
            private final XorCsrfChannelInterceptor csrf = new XorCsrfChannelInterceptor();
            private final Authorization authorization = new Authorization();
            @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                try { csrf.preSend(message, channel); return authorization.preSend(message, channel); }
                catch (AccessDeniedException | IllegalStateException denied) {
                    // Close without logging the rejected frame/native CSRF headers or client text.
                    var h = org.springframework.messaging.simp.SimpMessageHeaderAccessor.wrap(message);
                    connections.getObject().reject(h.getSessionId()); return null;
                }
            }
        });
    }
    @Override public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(2).maxPoolSize(4).queueCapacity(256);
        registration.interceptors(new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                var h = org.springframework.messaging.simp.SimpMessageHeaderAccessor.wrap(message);
                if (h.getMessageType() == org.springframework.messaging.simp.SimpMessageType.MESSAGE
                    && !connections.getObject().live(h.getSessionId(), h.getDestination())) return null;
                return message;
            }
        });
    }
    @Override public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(16384).setSendBufferSizeLimit(65536).setSendTimeLimit(10000).setTimeToFirstMessage(10000);
        registration.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                connections.getObject().opened(session);
                if (session.isOpen()) super.afterConnectionEstablished(session);
            }
            @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                connections.getObject().closed(session.getId()); super.afterConnectionClosed(session, status);
            }
        });
    }
    static final class Authorization implements ChannelInterceptor {
        @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
            var h = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (h == null) throw new AccessDeniedException("SUPPORT_DENIED");
            if (h.getCommand() == StompCommand.DISCONNECT) return message;
            var attrs = h.getSessionAttributes();
            HttpSession session = attrs == null ? null : (HttpSession) attrs.get(SupportSessions.HTTP);
            if (!SupportSessions.alive(session)) throw new AccessDeniedException("SUPPORT_DENIED");
            SupportAdmission.frame(session);
            String dest = h.getDestination(); boolean admin = SupportSessions.admin(session);
            if (h.getCommand() == StompCommand.CONNECT || h.getCommand() == StompCommand.STOMP
                || h.getCommand() == StompCommand.UNSUBSCRIBE || h.getCommand() == null) return message;
            boolean allowed = h.getCommand() == StompCommand.SUBSCRIBE
                ? "/user/queue/support.events".equals(dest) || "/user/queue/support.errors".equals(dest) || (admin && "/user/queue/support.admin".equals(dest))
                : h.getCommand() == StompCommand.SEND && ("/app/support/visitor.send".equals(dest) || (admin && "/app/support/admin.send".equals(dest)));
            if (!allowed) throw new AccessDeniedException("SUPPORT_DENIED");
            if (h.getCommand() == StompCommand.SUBSCRIBE) {
                @SuppressWarnings("unchecked")
                var subscriptions = (java.util.Set<String>) attrs.computeIfAbsent("support.subscriptions", key -> java.util.concurrent.ConcurrentHashMap.<String>newKeySet());
                String id = h.getSubscriptionId();
                if (id == null || (!subscriptions.contains(id) && subscriptions.size() >= 4)) throw new AccessDeniedException("SUPPORT_DENIED");
                subscriptions.add(id);
            }
            return message;
        }
    }
}
