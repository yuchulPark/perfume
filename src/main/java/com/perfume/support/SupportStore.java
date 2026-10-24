package com.perfume.support;

import static com.perfume.support.SupportModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** All SQL names are qualified. This module never initializes or migrates a database. */
public final class SupportStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public SupportStore(DataSource source) {
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        jdbc.setQueryTimeout(10);
    }
    public void verifyReady() {
        jdbc.queryForList("SELECT id, visitor_key, status, next_sequence, admin_read_sequence FROM support.conversations WHERE 1=0");
        jdbc.queryForList("SELECT id, sequence, client_message_id, message_text FROM support.messages WHERE 1=0");
    }
    private static Instant instant(ResultSet row, String key) throws SQLException {
        Timestamp value = row.getTimestamp(key); return value == null ? null : value.toInstant();
    }
    private static Conversation conversation(ResultSet r, int index) throws SQLException {
        return new Conversation(r.getObject("id", UUID.class), r.getString("status"), instant(r, "created_at"),
            instant(r, "updated_at"), instant(r, "closed_at"), instant(r, "last_message_at"), r.getString("last_message"),
            r.getLong("next_sequence"), r.getLong("unread_count"));
    }
    private static ChatMessage message(ResultSet r, int index) throws SQLException {
        return new ChatMessage(r.getObject("id", UUID.class), r.getObject("conversation_id", UUID.class), r.getLong("sequence"),
            r.getObject("client_message_id", UUID.class), r.getString("sender_type"), r.getString("message_text"), instant(r, "created_at"));
    }
    private static final String ROOMS = "SELECT c.*, (SELECT count(*) FROM support.messages m WHERE m.conversation_id=c.id AND m.sender_type='VISITOR' AND m.sequence>c.admin_read_sequence) AS unread_count FROM support.conversations c ";
    public Conversation current(UUID visitor) {
        var rooms = jdbc.query(ROOMS + "WHERE visitor_key=?", SupportStore::conversation, visitor);
        return rooms.isEmpty() ? null : rooms.get(0);
    }
    public Conversation create(UUID visitor) {
        var old = current(visitor); if (old != null) return old;
        Instant now = Instant.now(); UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO support.conversations(id,visitor_key,status,created_at,updated_at) VALUES (?,?,'OPEN',?,?)", id, visitor, Timestamp.from(now), Timestamp.from(now));
        } catch (org.springframework.dao.DuplicateKeyException race) { return current(visitor); }
        return get(id, visitor, false);
    }
    public Conversation get(UUID id, UUID visitor, boolean admin) {
        if (id == null) throw SupportFailure.missing();
        var rows = jdbc.query(ROOMS + "WHERE c.id=?" + (admin ? "" : " AND c.visitor_key=?"), SupportStore::conversation,
            admin ? new Object[]{id} : new Object[]{id, visitor});
        if (rows.isEmpty()) throw SupportFailure.missing();
        return rows.get(0);
    }
    public UUID owner(UUID id) { return jdbc.queryForObject("SELECT visitor_key FROM support.conversations WHERE id=?", UUID.class, id); }
    public Rooms list(int offset) {
        if (offset < 0 || offset > 100000) throw new SupportFailure(400, "SUPPORT_INVALID_REQUEST");
        var rows = jdbc.query(ROOMS + "ORDER BY c.updated_at DESC,c.id LIMIT 51 OFFSET ?", SupportStore::conversation, offset);
        return new Rooms(rows.stream().limit(50).toList(), offset + Math.min(rows.size(), 50), rows.size() > 50);
    }
    public History history(UUID id, UUID visitor, boolean admin, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) throw new SupportFailure(400, "SUPPORT_INVALID_REQUEST");
        var room = get(id, visitor, admin);
        List<ChatMessage> rows = jdbc.query("SELECT * FROM support.messages WHERE conversation_id=? AND sequence>? ORDER BY sequence LIMIT ?", SupportStore::message, id, after, limit + 1);
        var page = rows.stream().limit(limit).toList();
        return new History(room, page, page.isEmpty() ? after : page.get(page.size()-1).sequence(), rows.size() > limit);
    }
    public Event send(Send request, UUID visitor, boolean admin) {
        if (request == null || request.conversationId() == null || request.clientMessageId() == null || request.text() == null
            || request.text().isBlank() || request.text().length() > 2000 || request.text().codePoints().anyMatch(c -> c == 0 || (Character.isISOControl(c) && c != '\n' && c != '\t')))
            throw new SupportFailure(400, "SUPPORT_INVALID_MESSAGE");
        return tx.execute(status -> {
            lock(request.conversationId());
            var room = get(request.conversationId(), visitor, admin);
            String sender = admin ? "ADMIN" : "VISITOR";
            var existing = jdbc.query("SELECT * FROM support.messages WHERE conversation_id=? AND sender_type=? AND client_message_id=?", SupportStore::message, room.id(), sender, request.clientMessageId());
            if (!existing.isEmpty()) {
                if (!existing.get(0).text().equals(request.text().strip())) throw new SupportFailure(409, "SUPPORT_ID_CONFLICT");
                return new Event("MESSAGE", room, existing.get(0));
            }
            if (!room.status().equals("OPEN")) throw new SupportFailure(409, "SUPPORT_CLOSED");
            if (room.nextSequence() >= 10000) throw new SupportFailure(409, "SUPPORT_MESSAGE_LIMIT");
            Instant now = Instant.now();
            Long recent = jdbc.queryForObject("SELECT count(*) FROM support.messages WHERE conversation_id=? AND sender_type=? AND created_at>?", Long.class, room.id(), sender, Timestamp.from(now.minusSeconds(10)));
            if (recent != null && recent >= 10) throw new SupportFailure(429, "SUPPORT_RATE_LIMIT");
            var message = new ChatMessage(UUID.randomUUID(), room.id(), room.nextSequence()+1, request.clientMessageId(), sender, request.text().strip(), now);
            jdbc.update("INSERT INTO support.messages(id,conversation_id,sequence,client_message_id,sender_type,message_text,created_at) VALUES (?,?,?,?,?,?,?)",
                message.id(), room.id(), message.sequence(), message.clientMessageId(), sender, message.text(), Timestamp.from(now));
            jdbc.update("UPDATE support.conversations SET next_sequence=?,last_message=?,last_message_at=?,updated_at=? WHERE id=?",
                message.sequence(), message.text(), Timestamp.from(now), Timestamp.from(now), room.id());
            return new Event("MESSAGE", get(room.id(), visitor, admin), message);
        });
    }
    private void lock(UUID id) {
        if (jdbc.queryForList("SELECT id FROM support.conversations WHERE id=? FOR UPDATE", id).isEmpty()) throw SupportFailure.missing();
    }
    public Event close(UUID id) {
        return tx.execute(status -> {
            lock(id); Instant now = Instant.now();
            jdbc.update("UPDATE support.conversations SET status='CLOSED',closed_at=?,updated_at=? WHERE id=? AND status='OPEN'", Timestamp.from(now), Timestamp.from(now), id);
            return new Event("CLOSED", get(id, null, true), null);
        });
    }
    public Conversation read(UUID id, long sequence) {
        return tx.execute(status -> {
            lock(id); var room = get(id, null, true);
            if (sequence < 0 || sequence > room.nextSequence()) throw new SupportFailure(400, "SUPPORT_INVALID_REQUEST");
            jdbc.update("UPDATE support.conversations SET admin_read_sequence=greatest(admin_read_sequence,?) WHERE id=?", sequence, id);
            return get(id, null, true);
        });
    }
}
