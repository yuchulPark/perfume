package com.perfume.support;

import static org.junit.jupiter.api.Assertions.*;
import com.perfume.support.SupportModels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SupportStoreTests {
    SupportStore store;
    JdbcTemplate jdbc;
    UUID visitor, stranger;
    Conversation room;
    @BeforeEach void database() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:support_store_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute(Files.readString(Path.of("docs/sql/support-schema.sql")).replace("timestamptz", "timestamp with time zone"));
        store = new SupportStore(source); store.verifyReady();
        visitor = UUID.randomUUID(); stranger = UUID.randomUUID(); room = store.create(visitor);
    }
    @Test void currentIsIdempotentAndOpaqueOwnerNeverAppearsInDto() {
        assertEquals(room.id(), store.create(visitor).id());
        assertEquals(room.id(), store.current(visitor).id()); assertNull(store.current(stranger));
        assertFalse(room.toString().contains(visitor.toString()));
        assertEquals(404, assertThrows(SupportFailure.class, () -> store.history(room.id(), stranger, false, 0, 50)).status());
        assertEquals(404, assertThrows(SupportFailure.class, () -> store.send(new Send(room.id(), UUID.randomUUID(), "spoof"), stranger, false)).status());
    }
    @Test void persistedSequencePaginationUnreadAndIdempotenceAreStable() {
        UUID client = UUID.randomUUID();
        var first = store.send(new Send(room.id(), client, "방문자 메시지"), visitor, false);
        assertEquals(first.message().id(), store.send(new Send(room.id(), client, "방문자 메시지"), visitor, false).message().id());
        store.send(new Send(room.id(), UUID.randomUUID(), "관리자 답변"), null, true);
        store.send(new Send(room.id(), UUID.randomUUID(), "추가 질문"), visitor, false);
        assertEquals(2, store.get(room.id(), null, true).unreadCount());
        var page = store.history(room.id(), visitor, false, 0, 2);
        assertEquals(java.util.List.of(1L,2L), page.messages().stream().map(ChatMessage::sequence).toList()); assertTrue(page.hasMore());
        assertEquals(3L, store.history(room.id(), visitor, false, page.nextAfter(), 2).messages().get(0).sequence());
        assertEquals(1, store.read(room.id(), 1).unreadCount());
        assertEquals(1, store.read(room.id(), 0).unreadCount());
        assertEquals(0, store.read(room.id(), 3).unreadCount());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM support.messages", Integer.class));
    }
    @Test void closedRoomsRemainReadableAndExistingAcknowledgmentCanBeRecovered() {
        var request = new Send(room.id(), UUID.randomUUID(), "before close"); var event = store.send(request, visitor, false);
        assertEquals("CLOSED", store.close(room.id()).conversation().status());
        assertNotNull(store.get(room.id(), visitor, false).closedAt());
        assertEquals(event.message().id(), store.send(request, visitor, false).message().id());
        assertEquals("SUPPORT_CLOSED", assertThrows(SupportFailure.class, () -> store.send(new Send(room.id(), UUID.randomUUID(), "late"), null, true)).code());
        assertEquals(1, store.history(room.id(), visitor, false, 0, 10).messages().size());
        assertEquals(room.id(), store.create(visitor).id());
    }
    @Test void invalidMessagesLimitsAndIdReuseNeverModifyPersistence() {
        for (String text : new String[]{"", "  \n\t", "x".repeat(2001), "bad\u0000text"})
            assertEquals(400, assertThrows(SupportFailure.class, () -> store.send(new Send(room.id(), UUID.randomUUID(), text), visitor, false)).status());
        assertThrows(SupportFailure.class, () -> store.send(new Send(room.id(), null, "a"), visitor, false));
        var request = new Send(room.id(), UUID.randomUUID(), "first"); store.send(request, visitor, false);
        assertEquals(409, assertThrows(SupportFailure.class, () -> store.send(new Send(room.id(), request.clientMessageId(), "changed"), visitor, false)).status());
        assertEquals(1, store.history(room.id(), visitor, false, 0, 10).messages().size());
        assertThrows(SupportFailure.class, () -> store.read(room.id(), 99));
        assertThrows(SupportFailure.class, () -> store.history(room.id(), visitor, false, -1, 50));
        assertThrows(SupportFailure.class, () -> store.history(room.id(), visitor, false, 0, 101));
    }
    @Test void messageFrequencyIsLimitedButDuplicateRetryDoesNotConsumeQuota() {
        for (int i=0;i<10;i++) store.send(new Send(room.id(), UUID.randomUUID(), "message " + i), visitor, false);
        var existing = store.history(room.id(), visitor, false, 0, 1).messages().get(0);
        assertEquals(existing.id(), store.send(new Send(room.id(), existing.clientMessageId(), existing.text()), visitor, false).message().id());
        assertEquals(429, assertThrows(SupportFailure.class, () -> store.send(new Send(room.id(), UUID.randomUUID(), "too fast"), visitor, false)).status());
        assertEquals(10, store.history(room.id(), visitor, false, 0, 50).messages().size());
    }
    @Test void concurrentDuplicateSendsCommitExactlyOnce() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        var request = new Send(room.id(), UUID.randomUUID(), "same request");
        try {
            var tasks = java.util.stream.IntStream.range(0,4).mapToObj(i -> pool.submit(() -> store.send(request, visitor, false).message().id())).toList();
            for (var task : tasks) assertEquals(tasks.get(0).get(), task.get());
            assertEquals(1, store.history(room.id(), visitor, false, 0, 50).messages().size());
        } finally { pool.shutdownNow(); }
    }
    @Test void noOtherSchemaIsInitialized() {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema IN ('AUTH','AI')", Integer.class));
    }
}
