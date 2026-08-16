package io.quarkus.elasticsearch.restclient.vertx.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.Test;

import io.quarkus.elasticsearch.restclient.vertx.BackoffStrategy;

class BackoffStrategyTest {

    private static final Duration BASE = Duration.ofSeconds(1);
    private static final Duration CAP = Duration.ofSeconds(30);

    // -- fixed --

    @Test
    void fixedReturnsSameDelayForEveryAttempt() {
        BackoffStrategy fixed = BackoffStrategy.fixed(Duration.ofSeconds(5));
        assertThat(fixed.delayFor(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(fixed.delayFor(2)).isEqualTo(Duration.ofSeconds(5));
        assertThat(fixed.delayFor(100)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void fixedAllowsZero() {
        assertThat(BackoffStrategy.fixed(Duration.ZERO).delayFor(1)).isEqualTo(Duration.ZERO);
    }

    @Test
    void fixedRejectsNegativeOrNull() {
        assertThatThrownBy(() -> BackoffStrategy.fixed(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackoffStrategy.fixed(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -- exponential: growth and cap (jitter pinned to its maximum) --

    @Test
    void exponentialGrowsByFactorUntilCapped() {
        // random fraction == 1.0 -> full jitter yields the whole (capped) delay
        BackoffStrategy exp = exponentialWithFraction(BASE, 2.0, CAP, () -> 1.0);
        assertThat(exp.delayFor(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(exp.delayFor(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(exp.delayFor(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(exp.delayFor(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(exp.delayFor(5)).isEqualTo(Duration.ofSeconds(16));
        // 2^5 = 32s would exceed the 30s cap
        assertThat(exp.delayFor(6)).isEqualTo(CAP);
        assertThat(exp.delayFor(100)).isEqualTo(CAP);
    }

    @Test
    void exponentialLargeAttemptDoesNotOverflow() {
        BackoffStrategy exp = exponentialWithFraction(BASE, 2.0, CAP, () -> 1.0);
        // A huge exponent saturates to +Infinity in double space, then clamps to the cap.
        assertThat(exp.delayFor(Integer.MAX_VALUE)).isEqualTo(CAP);
    }

    // -- exponential: full jitter --

    @Test
    void fullJitterZeroFractionYieldsZero() {
        BackoffStrategy exp = exponentialWithFraction(BASE, 2.0, CAP, () -> 0.0);
        assertThat(exp.delayFor(1)).isEqualTo(Duration.ZERO);
        assertThat(exp.delayFor(10)).isEqualTo(Duration.ZERO);
    }

    @Test
    void fullJitterScalesWithFraction() {
        BackoffStrategy exp = exponentialWithFraction(BASE, 2.0, CAP, () -> 0.5);
        // delayFor(3) uncapped == 4s; half jitter -> 2s
        assertThat(exp.delayFor(3)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void realExponentialStaysWithinCap() {
        BackoffStrategy exp = BackoffStrategy.exponential(BASE, 2.0, CAP);
        for (int attempt = 1; attempt <= 50; attempt++) {
            Duration d = exp.delayFor(attempt);
            assertThat(d).isGreaterThanOrEqualTo(Duration.ZERO);
            assertThat(d).isLessThanOrEqualTo(CAP);
        }
    }

    // -- validation --

    @Test
    void exponentialRejectsInvalidArguments() {
        assertThatThrownBy(() -> BackoffStrategy.exponential(Duration.ZERO, 2.0, CAP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackoffStrategy.exponential(Duration.ofSeconds(-1), 2.0, CAP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackoffStrategy.exponential(BASE, 0.5, CAP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackoffStrategy.exponential(Duration.ofSeconds(10), 2.0, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delayForRejectsNonPositiveAttempts() {
        BackoffStrategy exp = BackoffStrategy.exponential(BASE, 2.0, CAP);
        assertThatThrownBy(() -> exp.delayFor(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exp.delayFor(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    // -- default --

    @Test
    void defaultIsCappedExponential() {
        assertThat(BackoffStrategy.DEFAULT).isNotNull();
        for (int attempt = 1; attempt <= 20; attempt++) {
            Duration d = BackoffStrategy.DEFAULT.delayFor(attempt);
            assertThat(d).isGreaterThanOrEqualTo(Duration.ZERO);
            assertThat(d).isLessThanOrEqualTo(Duration.ofSeconds(30));
        }
    }

    private static BackoffStrategy exponentialWithFraction(Duration base, double factor, Duration cap,
            DoubleSupplier fraction) {
        return new ExponentialBackoffStrategy(base, factor, cap, fraction);
    }
}
