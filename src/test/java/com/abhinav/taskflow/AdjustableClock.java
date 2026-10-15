package com.abhinav.taskflow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The application's Clock in tests: real time plus an offset that a test can move forward.
 *
 * <p>It keeps ticking (unlike {@code Clock.fixed}), so tests that never touch it behave exactly as with
 * {@code Clock.systemUTC()}, and two calls a millisecond apart still see different instants. A test that needs
 * "11 seconds later" or "past the lockout" calls {@link #advance} instead of sleeping.
 *
 * <p>One instance is shared by the whole cached application context (see TestcontainersConfiguration), so a test
 * that moves it must {@link #reset()} it in {@code @BeforeEach}: otherwise the next test class inherits the offset.
 * Thread-safe, because the app reads it on Tomcat threads while the test moves it on its own.
 */
public final class AdjustableClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    /** Moves "now" forward (or back, with a negative duration) for every reader of this clock. */
    public void advance(Duration duration) {
        offset.accumulateAndGet(duration, Duration::plus);
    }

    /** Back to real time. */
    public void reset() {
        offset.set(Duration.ZERO);
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** The application only ever reads instants; a zone-shifted copy would silently stop following advance(). */
    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("AdjustableClock is UTC only");
    }
}
