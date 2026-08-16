package io.quarkus.elasticsearch.restclient.vertx.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import io.quarkus.elasticsearch.restclient.vertx.BackoffStrategy;

/**
 * Capped exponential backoff with full jitter. See
 * {@link BackoffStrategy#exponential(Duration, double, Duration)}.
 */
public final class ExponentialBackoffStrategy implements BackoffStrategy {

    private final long baseNanos;
    private final double factor;
    private final long capNanos;
    // Supplies a uniform value in [0, 1) used to apply full jitter; injectable so tests can
    // make the (otherwise random) delay deterministic.
    private final DoubleSupplier randomFraction;

    public ExponentialBackoffStrategy(Duration base, double factor, Duration cap) {
        this(base, factor, cap, () -> ThreadLocalRandom.current().nextDouble());
    }

    ExponentialBackoffStrategy(Duration base, double factor, Duration cap, DoubleSupplier randomFraction) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(cap, "cap");
        if (base.isNegative() || base.isZero()) {
            throw new IllegalArgumentException("base must be positive");
        }
        if (factor < 1.0) {
            throw new IllegalArgumentException("factor must be >= 1");
        }
        if (cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("cap must be >= base");
        }
        this.baseNanos = base.toNanos();
        this.factor = factor;
        this.capNanos = cap.toNanos();
        this.randomFraction = Objects.requireNonNull(randomFraction, "randomFraction");
    }

    @Override
    public Duration delayFor(int failedAttempts) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("failedAttempts must be >= 1, got: " + failedAttempts);
        }
        // base * factor^(n-1), computed in double space so a large exponent saturates to
        // +Infinity rather than overflowing a long; it is then clamped to the cap.
        double raw = baseNanos * Math.pow(factor, failedAttempts - 1);
        long capped = raw >= capNanos ? capNanos : (long) raw;
        // Full jitter: uniform in [0, capped].
        long jittered = (long) (capped * randomFraction.getAsDouble());
        return Duration.ofNanos(jittered);
    }
}
