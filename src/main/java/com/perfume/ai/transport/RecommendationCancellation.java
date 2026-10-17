package com.perfume.ai.transport;

import java.util.LinkedHashSet;
import java.util.Set;

/** One recommendation's local threads/transports. Never contains questions, results or credentials. */
public final class RecommendationCancellation {
    private static final ThreadLocal<RecommendationCancellation> CURRENT = new ThreadLocal<>();
    private final Set<Registration> actions = new LinkedHashSet<>();
    private boolean cancelled;
    private int children;
    private static final class Registration implements AutoCloseable {
        private final Runnable action;
        private boolean active = true;
        Registration(Runnable action) { this.action = action; }
        synchronized void run() { if (active) { active = false; action.run(); } }
        @Override public synchronized void close() { active = false; }
    }

    public static RecommendationCancellation current() { return CURRENT.get(); }
    public synchronized boolean isCancelled() { return cancelled; }
    public AutoCloseable onCancel(Runnable action) {
        var registration = new Registration(action);
        boolean run;
        synchronized (this) { run = cancelled; if (!run) actions.add(registration); }
        if (run) registration.run();
        return () -> { registration.close(); synchronized (this) { actions.remove(registration); } };
    }
    public void cancel() {
        Registration[] pending;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true; pending = actions.toArray(Registration[]::new);
        }
        for (var action : pending) { try { action.run(); } catch (RuntimeException ignored) { /* Other resources must still receive cancellation. */ } }
    }
    public AutoCloseable bind() {
        var previous = CURRENT.get(); var thread = Thread.currentThread();
        CURRENT.set(this);
        var registration = onCancel(thread::interrupt);
        return () -> { registration.close(); if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public synchronized AutoCloseable child() {
        children++;
        return new AutoCloseable() {
            private boolean closed;
            @Override public void close() {
                synchronized (RecommendationCancellation.this) {
                    if (!closed) { closed = true; children--; RecommendationCancellation.this.notifyAll(); }
                }
            }
        };
    }
    /** An interrupted Future is not proof that its search worker has exited. */
    public void awaitChildren() {
        boolean interrupted = Thread.interrupted();
        synchronized (this) {
            while (children != 0) {
                try { wait(); } catch (InterruptedException stop) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
