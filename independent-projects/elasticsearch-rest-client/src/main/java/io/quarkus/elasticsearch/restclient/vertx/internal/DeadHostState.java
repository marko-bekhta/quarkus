package io.quarkus.elasticsearch.restclient.vertx.internal;

import java.util.Objects;
import java.util.function.Supplier;

import io.quarkus.elasticsearch.restclient.vertx.BackoffStrategy;

/**
 * Tracks the backoff state for a dead Elasticsearch node. The delay before a dead node is
 * retried is delegated to a pluggable {@link BackoffStrategy}, computed from the number of
 * consecutive failed attempts.
 */
final class DeadHostState implements Comparable<DeadHostState> {

    private final int failedAttempts;
    private final long deadUntilNanos;
    private final Supplier<Long> nanoTimeSupplier;
    private final BackoffStrategy backoffStrategy;

    DeadHostState(Supplier<Long> nanoTimeSupplier, BackoffStrategy backoffStrategy) {
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier);
        this.backoffStrategy = Objects.requireNonNull(backoffStrategy);
        this.failedAttempts = 1;
        this.deadUntilNanos = nanoTimeSupplier.get() + backoffStrategy.delayFor(failedAttempts).toNanos();
    }

    DeadHostState(DeadHostState previousState) {
        this.nanoTimeSupplier = previousState.nanoTimeSupplier;
        this.backoffStrategy = previousState.backoffStrategy;
        this.failedAttempts = previousState.failedAttempts + 1;
        this.deadUntilNanos = nanoTimeSupplier.get() + backoffStrategy.delayFor(failedAttempts).toNanos();
    }

    boolean shallBeRetried() {
        return nanoTimeSupplier.get() >= deadUntilNanos;
    }

    boolean shallBeRetried(long nowNanos) {
        return nowNanos >= deadUntilNanos;
    }

    long getDeadUntilNanos() {
        return deadUntilNanos;
    }

    int getFailedAttempts() {
        return failedAttempts;
    }

    @Override
    public int compareTo(DeadHostState other) {
        if (nanoTimeSupplier != other.nanoTimeSupplier) {
            throw new IllegalArgumentException("Cannot compare DeadHostState instances with different time suppliers");
        }
        return Long.compare(deadUntilNanos, other.deadUntilNanos);
    }

    @Override
    public String toString() {
        return "DeadHostState{failedAttempts=" + failedAttempts
                + ", deadUntilNanos=" + deadUntilNanos + '}';
    }
}
