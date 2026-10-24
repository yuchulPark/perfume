package com.perfume.support;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Type;
import com.fasterxml.jackson.databind.*;
import com.perfume.support.SupportModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(classes = SupportTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "support.test.fixture=true", "server.address=127.0.0.1", "perfume.auth.enabled=true", "server.servlet.session.cookie.secure=false",
    "perfume.support.enabled=true", "perfume.support.allowed-origins=http://support.test", "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never",
    "logging.level.root=WARN", "debug=false"})
class SupportApiTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired SupportStore store;
    class Browser {
        final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(java.time.Duration.ofSeconds(5))
            .cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        HttpResponse<String> call(String method, String path, String body, boolean token) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(java.time.Duration.ofSeconds(10));
            if (token && csrf != null) builder.header("X-CSRF-TOKEN", csrf);
            if (body != null) builder.header("Content-Type", "application/json");
            return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        void csrf() throws Exception { csrf = json.readTree(call("GET", "/api/auth/csrf", null, false).body()).get("token").asText(); }
        UUID create() throws Exception {
            csrf(); var response = call("POST", "/api/support/conversations", null, true); assertEquals(200, response.statusCode());
            return UUID.fromString(json.readTree(response.body()).get("id").asText());
        }
        void login() throws Exception {
            csrf();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded").header("X-CSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=" + java.net.URLEncoder.encode(SupportTestApplication.PASSWORD, java.nio.charset.StandardCharsets.UTF_8))).build();
            assertEquals(200, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()); csrf();
        }
        String cookies() {
            var manager = (java.net.CookieManager)http.cookieHandler().orElseThrow();
            return manager.getCookieStore().getCookies().stream().map(c -> c.getName()+"="+c.getValue()).collect(java.util.stream.Collectors.joining("; "));
        }
    }
    class Socket implements AutoCloseable {
        final WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        final AtomicReference<Throwable> transportError = new AtomicReference<>();
        StompSession session;
        Socket(Browser browser, boolean admin) throws Exception {
            var converter = new MappingJackson2MessageConverter(); converter.setObjectMapper(json); client.setMessageConverter(converter);
            var http = new WebSocketHttpHeaders(); http.setOrigin("http://support.test"); http.add(HttpHeaders.COOKIE, browser.cookies());
            var headers = new StompHeaders(); headers.add("X-CSRF-TOKEN", browser.csrf);
            session = client.connectAsync("ws://127.0.0.1:"+port+"/ws/support", http, headers, new StompSessionHandlerAdapter() {
                @Override public void handleTransportError(StompSession session, Throwable error) { transportError.set(error); }
            }).get(10, TimeUnit.SECONDS);
            session.subscribe(admin ? "/user/queue/support.admin" : "/user/queue/support.events", new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders headers) { return Event.class; }
                @Override public void handleFrame(StompHeaders headers, Object body) { events.add((Event)body); }
            });
        }
        Event message(UUID clientId) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < until) {
                var event = events.poll(100, TimeUnit.MILLISECONDS);
                if (event != null && event.message() != null && event.message().clientMessageId().equals(clientId)) return event;
            }
            fail("Expected a persisted personal support message"); return null;
        }
        void send(UUID room, UUID clientId, String text, boolean admin) { session.send(admin ? "/app/support/admin.send" : "/app/support/visitor.send", new Send(room,clientId,text)); }
        @Override public void close() { if (session != null && session.isConnected()) session.disconnect(); client.stop(); }
    }
    @Test void anonymousCreateRestoreIsolationAdminAuthorizationAndRestCsrf() throws Exception {
        var a = new Browser(); var b = new Browser(); UUID room = a.create(); b.csrf();
        assertEquals(room.toString(), json.readTree(a.call("GET", "/api/support/conversations/current", null, false).body()).get("conversation").get("id").asText());
        assertEquals(room, a.create());
        assertEquals(404, b.call("GET", "/api/support/conversations/"+room+"/messages", null, false).statusCode());
        assertEquals(403, b.call("POST", "/api/support/conversations", null, false).statusCode());
        assertTrue(java.util.List.of(401,403).contains(b.call("GET", "/api/admin/support/conversations", null, false).statusCode()));
        assertEquals(401, b.call("GET", "/api/ai/perfumes/recommend/jobs", null, false).statusCode());
        var admin = new Browser(); admin.login();
        assertEquals(200, admin.call("GET", "/api/admin/support/conversations", null, false).statusCode());
        assertEquals(403, admin.call("POST", "/api/admin/support/conversations/"+room+"/close", null, false).statusCode());
        assertEquals(200, admin.call("POST", "/api/admin/support/conversations/"+room+"/close", null, true).statusCode());
        assertTrue(a.call("GET", "/api/support/conversations/"+room+"/messages", null, false).body().contains("CLOSED"));
    }
    @Test void realStompVisitorAdminConversationIsolationPersistenceAndReconnectRecovery() throws Exception {
        var a = new Browser(); var b = new Browser(); var admin = new Browser();
        UUID ar = a.create(), br = b.create(); admin.login();
        try (var as = new Socket(a,false); var bs = new Socket(b,false); var ads = new Socket(admin,true)) {
            // Wait for the broker's inbound subscription queue before measuring live notifications.
            Thread.sleep(150);
            UUID request = UUID.randomUUID(); as.send(ar,request,"visitor A",false);
            assertEquals("VISITOR", as.message(request).message().senderType());
            assertEquals(ar, ads.message(request).conversation().id());
            assertNull(bs.events.poll(250,TimeUnit.MILLISECONDS));
            UUID reply = UUID.randomUUID(); ads.send(ar,reply,"administrator reply",true);
            assertEquals("ADMIN", as.message(reply).message().senderType()); ads.message(reply);
            assertNull(bs.events.poll(250,TimeUnit.MILLISECONDS));
            as.send(ar,request,"visitor A",false); as.message(request);
            assertEquals(2, store.history(ar,null,true,0,50).messages().size());
            UUID bRequest = UUID.randomUUID(); bs.send(br,bRequest,"visitor B",false);
            assertEquals(br, bs.message(bRequest).conversation().id()); ads.message(bRequest);
            // A's queue can contain its duplicate acknowledgement, never B's message.
            assertTrue(as.events.stream().noneMatch(e -> e.conversation().id().equals(br)));
        }
        var recovered = json.readTree(a.call("GET","/api/support/conversations/"+ar+"/messages?after=1",null,false).body());
        assertEquals(1,recovered.get("messages").size()); assertEquals("administrator reply",recovered.get("messages").get(0).get("text").asText());
    }
    @Test void senderRoleSpoofingCannotBecomeAdminAndExpiredSocketCannotSend() throws Exception {
        var visitor = new Browser(); UUID room = visitor.create();
        try (var socket = new Socket(visitor,false)) {
            Thread.sleep(100); UUID client = UUID.randomUUID();
            var headers = new StompHeaders(); headers.setDestination("/app/support/visitor.send"); headers.setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
            socket.session.send(headers, Map.of("conversationId",room,"clientMessageId",client,"text","safe visitor","senderType","ADMIN","senderId","admin","role","ADMIN"));
            assertEquals("VISITOR",socket.message(client).message().senderType());
        }
        var admin = new Browser(); admin.login();
        try (var socket = new Socket(admin,true)) {
            assertEquals(200,admin.call("POST","/api/auth/logout",null,true).statusCode());
            UUID client = UUID.randomUUID();
            try { socket.send(room,client,"must not persist",true); } catch (RuntimeException closed) { }
            Thread.sleep(300);
            assertTrue(store.history(room,null,true,0,50).messages().stream().noneMatch(m -> m.clientMessageId().equals(client)));
            assertEquals(401,admin.call("GET","/api/admin/support/conversations",null,false).statusCode());
        }
    }
    @Test void websocketOriginAndConnectCsrfAreRequired() throws Exception {
        var visitor = new Browser(); visitor.create();
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        try {
            var h = new WebSocketHttpHeaders(); h.setOrigin("http://evil.invalid"); h.add(HttpHeaders.COOKIE,visitor.cookies());
            var connect = new StompHeaders(); connect.add("X-CSRF-TOKEN",visitor.csrf);
            assertThrows(ExecutionException.class, () -> client.connectAsync("ws://127.0.0.1:"+port+"/ws/support",h,connect,new StompSessionHandlerAdapter(){}).get(5,TimeUnit.SECONDS));
            h.setOrigin("http://support.test");
            assertThrows(ExecutionException.class, () -> client.connectAsync("ws://127.0.0.1:"+port+"/ws/support",h,new StompHeaders(),new StompSessionHandlerAdapter(){}).get(5,TimeUnit.SECONDS));
        } finally { client.stop(); }
    }
}
