package io.quarkus.elasticsearch.restclient.vertx.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.quarkus.elasticsearch.restclient.vertx.BackoffStrategy;

class DeadHostStateTest {

    // A deterministic strategy that returns a delay proportional to the attempt number,
    // so timing assertions are exact and independent of the default (jittered) strategy.
    private static final BackoffStrategy TEN_SECONDS_PER_ATTEMPT = failedAttempts -> Duration.ofSeconds(10L * failedAttempts);

    @Test
    void initialStateHasOneFailedAttemptAndDelegatesToStrategy() {
        AtomicLong clock = new AtomicLong(0);
        DeadHostState state = new DeadHostState(clock::get, TEN_SECONDS_PER_ATTEMPT);

        assertThat(state.getFailedAttempts()).isEqualTo(1);
        assertThat(state.getDeadUntilNanos()).isEqualTo(TimeUnit.SECONDS.toNanos(10));
    }

    @Test
    void eachFailureIncrementsAttemptsAndUsesStrategyDelay() {
        AtomicLong clock = new AtomicLong(0);
        DeadHostState state = new DeadHostState(clock::get, TEN_SECONDS_PER_ATTEMPT);
        assertThat(state.getFailedAttempts()).isEqualTo(1);
        assertThat(state.getDeadUntilNanos() - clock.get()).isEqualTo(TimeUnit.SECONDS.toNanos(10));

        for (int attempt = 2; attempt <= 5; attempt++) {
            clock.set(state.getDeadUntilNanos());
            state = new DeadHostState(state);
            assertThat(state.getFailedAttempts()).isEqualTo(attempt);
            assertThat(state.getDeadUntilNanos() - clock.get())
                    .isEqualTo(TimeUnit.SECONDS.toNanos(10L * attempt));
        }
    }

    @Test
    void fixedStrategyKeepsConstantDelayAcrossFailures() {
        AtomicLong clock = new AtomicLong(0);
        BackoffStrategy fixed = BackoffStrategy.fixed(Duration.ofSeconds(30));
        DeadHostState state = new DeadHostState(clock::get, fixed);

        for (int i = 0; i < 5; i++) {
            assertThat(state.getDeadUntilNanos() - clock.get()).isEqualTo(TimeUnit.SECONDS.toNanos(30));
            clock.set(state.getDeadUntilNanos());
            state = new DeadHostState(state);
        }
    }

    @Test
    void shallBeRetriedBeforeDeadline() {
        AtomicLong clock = new AtomicLong(0);
        DeadHostState state = new DeadHostState(clock::get, TEN_SECONDS_PER_ATTEMPT);

        clock.set(state.getDeadUntilNanos() - 1);
        assertThat(state.shallBeRetried()).isFalse();
    }

    @Test
    void shallBeRetriedAtDeadline() {
        AtomicLong clock = new AtomicLong(0);
        DeadHostState state = new DeadHostState(clock::get, TEN_SECONDS_PER_ATTEMPT);

        clock.set(state.getDeadUntilNanos());
        assertThat(state.shallBeRetried()).isTrue();
    }

    @Test
    void shallBeRetriedAfterDeadline() {
        AtomicLong clock = new AtomicLong(0);
        DeadHostState state = new DeadHostState(clock::get, TEN_SECONDS_PER_ATTEMPT);

        clock.set(state.getDeadUntilNanos() + 1);
        assertThat(state.shallBeRetried()).isTrue();
    }

    @Test
    void compareToOrdersByDeadUntil() {
        AtomicLong clock = new AtomicLong(0);
        Supplier<Long> supplier = clock::get;
        DeadHostState first = new DeadHostState(supplier, TEN_SECONDS_PER_ATTEMPT);

        clock.set(1000L);
        DeadHostState second = new DeadHostState(supplier, TEN_SECONDS_PER_ATTEMPT);

        assertThat(first.compareTo(second)).isLessThan(0);
        assertThat(second.compareTo(first)).isGreaterThan(0);
    }

    @Test
    void compareToDifferentSuppliersThrows() {
        DeadHostState a = new DeadHostState(() -> 0L, TEN_SECONDS_PER_ATTEMPT);
        DeadHostState b = new DeadHostState(() -> 0L, TEN_SECONDS_PER_ATTEMPT);

        assertThatThrownBy(() -> a.compareTo(b))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void immutabilityPreviousStateUnchanged() {
        AtomicLong clock = new AtomicLong(0);
        DeadHostState first = new DeadHostState(clock::get, TEN_SECONDS_PER_ATTEMPT);
        int firstAttempts = first.getFailedAttempts();
        long firstDeadUntil = first.getDeadUntilNanos();

        clock.set(first.getDeadUntilNanos());
        new DeadHostState(first);

        assertThat(first.getFailedAttempts()).isEqualTo(firstAttempts);
        assertThat(first.getDeadUntilNanos()).isEqualTo(firstDeadUntil);
    }
}
