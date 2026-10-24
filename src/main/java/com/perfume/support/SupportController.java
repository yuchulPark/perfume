package com.perfume.support;

import static com.perfume.support.SupportModels.*;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class SupportController {
    private final ObjectProvider<SupportStore> stores;
    private final ObjectProvider<SupportConnections> sockets;
    private final SupportAdmission admission = new SupportAdmission();
    public SupportController(ObjectProvider<SupportStore> stores, ObjectProvider<SupportConnections> sockets) { this.stores = stores; this.sockets = sockets; }
    private SupportStore store() {
        var store = stores.getIfAvailable(); if (store == null) throw new SupportFailure(503, "SUPPORT_DISABLED"); return store;
    }
    private <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    @GetMapping("/api/support/config") public ResponseEntity<Map<String,Boolean>> config() { return ok(Map.of("enabled", stores.getIfAvailable() != null)); }
    @PostMapping("/api/support/conversations") public ResponseEntity<Conversation> create(HttpSession session) {
        var store = store(); UUID visitor = SupportSessions.visitor(session);
        synchronized (session) {
            var before = store.current(visitor);
            if (before == null) admission.create();
            var room = store.create(visitor);
            if (before == null) sockets.getObject().publish(new Event("CREATED", room, null), visitor);
            return ok(room);
        }
    }
    @GetMapping("/api/support/conversations/current") public ResponseEntity<Map<String,Object>> current(HttpSession session) {
        var room = store().current(SupportSessions.visitor(session));
        return ok(room == null ? Map.of() : Map.of("conversation", room));
    }
    @GetMapping("/api/support/conversations/{id}/messages") public ResponseEntity<History> history(@PathVariable UUID id, HttpSession session,
            @RequestParam(defaultValue="0") long after, @RequestParam(defaultValue="50") int limit) {
        return ok(store().history(id, SupportSessions.visitor(session), false, after, limit));
    }
    @GetMapping("/api/admin/support/conversations") public ResponseEntity<Rooms> list(HttpSession session, @RequestParam(defaultValue="0") int offset) {
        SupportSessions.requireAdmin(session); return ok(store().list(offset));
    }
    @GetMapping("/api/admin/support/conversations/{id}/messages") public ResponseEntity<History> adminHistory(@PathVariable UUID id, HttpSession session,
            @RequestParam(defaultValue="0") long after, @RequestParam(defaultValue="50") int limit) {
        SupportSessions.requireAdmin(session); return ok(store().history(id, null, true, after, limit));
    }
    @PostMapping("/api/admin/support/conversations/{id}/close") public ResponseEntity<Conversation> close(@PathVariable UUID id, HttpSession session) {
        SupportSessions.requireAdmin(session); var store = store(); var event = store.close(id);
        sockets.getObject().publish(event, store.owner(id)); return ok(event.conversation());
    }
    @PostMapping("/api/admin/support/conversations/{id}/read") public ResponseEntity<Conversation> read(@PathVariable UUID id, @RequestBody Read request, HttpSession session) {
        SupportSessions.requireAdmin(session); var store = store(); var room = store.read(id, request.sequence());
        sockets.getObject().publish(new Event("READ", room, null), store.owner(id)); return ok(room);
    }
    @ExceptionHandler(SupportFailure.class) public ResponseEntity<Map<String,Object>> failed(SupportFailure failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore()).body(Map.of("code", failure.code(), "status", failure.status()));
    }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class}) public ResponseEntity<Map<String,Object>> database() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("code", "SUPPORT_UNAVAILABLE", "status", 503));
    }
}
