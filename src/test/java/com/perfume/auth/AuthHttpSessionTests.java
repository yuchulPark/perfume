package com.perfume.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** Real HTTP/Tomcat cookies and Spring Security; only the account database is an H2 fixture. */
@SpringBootTest(classes=AuthHttpSessionTests.Web.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"server.address=127.0.0.1", "perfume.auth.enabled=true", "perfume.auth.dev-http.enabled=true",
        "perfume.auth.dev-http.origin=http://192.168.0.19:8088", "spring.datasource.url=jdbc:postgresql://db:5432/perfume",
        "perfume.auth.http-session-test=true"})
@ActiveProfiles("auth-dev-http")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AuthHttpSessionTests {
    static final String PASSWORD = UUID.randomUUID()+"Az!";
    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="perfume.auth.http-session-test", havingValue="true")
    @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
    @Import({AuthSecurityConfiguration.class, AuthController.class})
    static class Web {
        @Bean AuthUserStore fixture() {
            // Activation guards have separate tests. No real PostgreSQL or Ollama connection exists here.
            var source = new DriverManagerDataSource("jdbc:h2:mem:http_session_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
            AuthUserStoreTests.schema(source);
            var store = new AuthUserStore(source, new BCryptPasswordEncoder(10));
            store.createAdministrator("admin", PASSWORD, false);
            return store;
        }
    }

    @Test void httpCookieSessionRotationCsrfAndLogoutWorkWithoutTlsOrModelCalls() throws Exception {
        var csrf = request("GET", "/api/auth/csrf", null, null, "");
        assertEquals(200, csrf.status());
        String cookie = csrf.cookie();
        assertTrue(cookie.startsWith("PERFUME_DEV_SESSION="), "HTTP must avoid an existing Secure JSESSIONID");
        assertTrue(csrf.headers().toLowerCase(Locale.ROOT).contains("httponly"));
        assertTrue(csrf.headers().toLowerCase(Locale.ROOT).contains("samesite=lax"));
        assertFalse(csrf.headers().toLowerCase(Locale.ROOT).contains("; secure"));
        String token = csrf.body(json).get("token").asText();
        assertEquals(403, request("POST", "/api/auth/login", cookie, null, "username=admin&password="+PASSWORD).status());
        var login = request("POST", "/api/auth/login", cookie, token, "username=admin&password="+PASSWORD);
        assertEquals(200, login.status());
        assertNotEquals(cookie, login.cookie());
        String session = login.cookie();
        assertEquals("admin", request("GET", "/api/auth/me", session, null, "").body(json).path("user").path("username").asText());
        assertEquals(403, request("POST", "/api/auth/logout", session, token, "").status(), "Pre-login CSRF must expire");
        String renewed = request("GET", "/api/auth/csrf", session, null, "").body(json).get("token").asText();
        assertEquals(403, request("POST", "/api/ai/perfumes/recommend/jobs", session, null, "{}").status());
        // No recommendation controller is loaded: 404 proves authentication passed without invoking a model.
        assertEquals(404, request("GET", "/api/ai/perfumes/recommend/jobs", session, null, "").status());
        var logout = request("POST", "/api/auth/logout", session, renewed, "");
        assertEquals(200, logout.status());
        assertTrue(logout.headers().toLowerCase(Locale.ROOT).contains("set-cookie: perfume_dev_session=;"));
        assertTrue(logout.headers().toLowerCase(Locale.ROOT).contains("max-age=0"));
        assertTrue(request("GET", "/api/auth/me", session, null, "").body(json).path("user").isNull());
        assertEquals(401, request("GET", "/api/ai/perfumes/recommend/jobs", session, null, "").status());
    }

    record Reply(int status, String headers, String content) {
        String cookie() {
            return Arrays.stream(headers.split("\r\n")).filter(line -> line.toLowerCase(Locale.ROOT).startsWith("set-cookie: perfume_dev_session="))
                .map(line -> line.substring("set-cookie: ".length()).split(";", 2)[0]).findFirst().orElseThrow();
        }
        JsonNode body(ObjectMapper mapper) throws Exception { return mapper.readTree(content); }
    }
    private Reply request(String method, String path, String cookie, String csrf, String body) throws Exception {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String headers = method+" "+path+" HTTP/1.1\r\nHost: 192.168.0.19:8088\r\nX-Real-IP: 192.168.0.50\r\nX-Forwarded-Proto: http\r\n"
            +"Origin: "+AuthDevelopmentHttp.ORIGIN+"\r\nConnection: close\r\n"
            +(cookie == null ? "" : "Cookie: "+cookie+"\r\n")+(csrf == null ? "" : "X-CSRF-TOKEN: "+csrf+"\r\n")
            +"Content-Type: application/x-www-form-urlencoded\r\nContent-Length: "+payload.length+"\r\n\r\n";
        String response;
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(payload);
            response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        String[] parts = response.split("\r\n\r\n", 2);
        String content = parts.length == 2 ? parts[1] : "";
        if (parts[0].toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked")) {
            var decoded = new StringBuilder();
            while (!content.startsWith("0\r\n")) {
                int end = content.indexOf("\r\n");
                int size = Integer.parseInt(content.substring(0, end).split(";", 2)[0], 16);
                decoded.append(content, end+2, end+2+size);
                content = content.substring(end+2+size+2);
            }
            content = decoded.toString();
        }
        return new Reply(Integer.parseInt(parts[0].split(" ")[1]), parts[0], content);
    }
}
