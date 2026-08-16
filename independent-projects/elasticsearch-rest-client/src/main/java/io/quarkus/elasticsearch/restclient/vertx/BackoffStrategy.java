package io.quarkus.elasticsearch.restclient.vertx;

import java.time.Duration;

import io.quarkus.elasticsearch.restclient.vertx.internal.ExponentialBackoffStrategy;

/**
 * Computes how long a node should be considered dead after a failure, as a function of the
 * number of consecutive failed attempts. This is the pluggable dead-node backoff policy:
 * consumers may supply their own implementation (it is a functional interface, so a lambda
 * works) or use one of the built-in factories, {@link #exponential(Duration, double, Duration)}
 * and {@link #fixed(Duration)}.
 * <p>
 * Configure it on a {@link RequestDispatcherFactory} via
 * {@link RequestDispatcherFactory#backoffStrategy(BackoffStrategy)}; the {@link #DEFAULT}
 * is used when none is set.
 */
@FunctionalInterface
public interface BackoffStrategy {

    /**
     * The default strategy: a capped exponential backoff with full jitter, using a 1s base,
     * a factor of 2, and a 30s cap.
     */
    BackoffStrategy DEFAULT = exponential(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30));

    /**
     * Returns how long the node should remain dead after its {@code failedAttempts}-th
     * consecutive failure.
     *
     * @param failedAttempts the number of consecutive failures, always {@code >= 1}
     *        ({@code 1} on the first failure)
     * @return a non-null, non-negative delay
     */
    Duration delayFor(int failedAttempts);

    /**
     * Creates a capped exponential backoff with full jitter. For the {@code n}-th failure the
     * uncapped delay is {@code base * factor^(n-1)}; it is capped at {@code cap} and then
     * uniformly randomized in {@code [0, cappedDelay]} (full jitter) to spread out retries and
     * avoid stampedes.
     *
     * @param base the base delay applied on the first failure (must be positive)
     * @param factor the growth factor per additional failure (must be {@code >= 1})
     * @param cap the maximum delay (must be {@code >= base})
     * @return a new strategy
     */
    static BackoffStrategy exponential(Duration base, double factor, Duration cap) {
        return new ExponentialBackoffStrategy(base, factor, cap);
    }

    /**
     * Creates a strategy that always returns the same delay, regardless of the number of
     * failed attempts.
     *
     * @param delay the delay (must be non-negative)
     * @return a new strategy
     */
    static BackoffStrategy fixed(Duration delay) {
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("delay must be non-negative");
        }
        return failedAttempts -> delay;
    }
}
