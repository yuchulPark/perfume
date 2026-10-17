package com.perfume.ai.embedding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One atomic checkpoint contains work ranges and the latest safe result. No catalog content. */
public final class AutoEmbeddingState implements AutoCloseable {
    public record Range(long afterId, long upperId) {
        public Range {
            if (afterId < 0 || upperId <= afterId) { throw new IllegalArgumentException("Invalid scan range."); }
        }
    }
    public record Report(String startedAt, String finishedAt, String status, String code, long elapsedMs,
            long total, PerfumeEmbeddingBatch.Result result, long nextCursor, int pendingRanges) { }
    public record Snapshot(int version, EmbeddingModelIdentity model, long observedUpperId, List<Range> pending,
            String lastSuccessAt, Report latest) {
        public Snapshot {
            if (version != 1 || model == null || observedUpperId < 0 || pending == null || pending.size() > 10000
                    || pending.stream().anyMatch(r -> r.upperId() > observedUpperId)) {
                throw new IllegalArgumentException("Invalid automatic embedding checkpoint.");
            }
            pending = List.copyOf(pending);
            var sorted = pending.stream().sorted(java.util.Comparator.comparingLong(Range::afterId)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).afterId() < sorted.get(i - 1).upperId()) {
                    throw new IllegalArgumentException("Overlapping automatic embedding ranges.");
                }
            }
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;

    public AutoEmbeddingState(Path directory) throws IOException {
        this.directory = directory;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("STATE_INVALID"); }
        var lockPath = directory.resolve("worker.lock");
        channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            lock = channel.tryLock();
            if (lock == null) { throw new IOException("STATE_BUSY"); }
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw new IOException("STATE_BUSY");
        }
    }

    public Snapshot read(EmbeddingModelIdentity model) throws IOException {
        var path = directory.resolve("state.json");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { return new Snapshot(1, model, 0, List.of(), null, null); }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 4_000_000) { throw new IOException("STATE_INVALID"); }
        try {
            var state = mapper.readValue(Files.readAllBytes(path), Snapshot.class);
            if (!state.model().equals(model)) { throw new IOException("STATE_MODEL_MISMATCH"); }
            return state;
        } catch (RuntimeException invalid) { throw new IOException("STATE_INVALID"); }
    }

    public void write(Snapshot state) throws IOException {
        Path temporary = Files.createTempFile(directory, ".checkpoint-", ".tmp");
        try {
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(mapper.writeValueAsBytes(state));
                while (bytes.hasRemaining()) { output.write(bytes); }
                output.force(true);
            }
            Files.move(temporary, directory.resolve("state.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Linux directory fsync makes the rename durable across power loss. Windows does not support it.
            if (!System.getProperty("os.name").startsWith("Windows")) {
                try (var parent = FileChannel.open(directory, StandardOpenOption.READ)) { parent.force(true); }
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    @Override public void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}
