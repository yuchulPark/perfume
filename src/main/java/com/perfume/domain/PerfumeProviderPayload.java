package com.perfume.domain;

import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Append-only distinct JSON snapshots. Identical responses reuse their existing row. */
@Entity
@Table(name = "perfume_provider_payloads", uniqueConstraints = @UniqueConstraint(name = "uk_perfume_provider_payloads_hash", columnNames = {"perfume_id", "tool_name", "payload_hash"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PerfumeProviderPayload {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "perfume_id", nullable = false) private Perfume perfume;
    @Column(name = "tool_name", nullable = false, length = 64) private String toolName;
    @Column(name = "payload_hash", nullable = false, length = 64) private String payloadHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false) private JsonNode payload;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;

    public PerfumeProviderPayload(Perfume perfume, String toolName, String payloadHash, JsonNode payload) {
        this.perfume = perfume; this.toolName = toolName; this.payloadHash = payloadHash;
        this.payload = payload.deepCopy(); this.receivedAt = Instant.now();
    }
}
