package com.lit.fire.flame;

import com.lit.fire.flame.models.UniversalPost;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HawkesIntensityCalculator}, focused on the degenerate inputs that
 * previously drove the BOBYQA optimizer into a
 * {@code MathIllegalStateException: trust region step has failed to reduce Q}.
 *
 * The estimator is exercised through the {@link Stream} overload, which does not touch
 * the database, so a {@code null} JDBC connection is sufficient.
 */
public class HawkesIntensityCalculatorTest {

    private static final long BASE_EPOCH = LocalDateTime.of(2026, 1, 1, 0, 0)
            .toEpochSecond(ZoneOffset.UTC);

    /** Builds a stream of posts whose timestamps are at the given second offsets. */
    private static Stream<UniversalPost> postsAtSeconds(List<Long> offsets) {
        return offsets.stream().map(off -> new UniversalPost(
                "postId", "authorId", "content",
                LocalDateTime.ofEpochSecond(BASE_EPOCH + off, 0, ZoneOffset.UTC),
                "platform", Collections.emptyMap()));
    }

    /** Asserts the parameters are finite and respect the model's bound constraints. */
    private static void assertValid(HawkesIntensityCalculator.HawkesParameters p, double beta) {
        assertTrue(Double.isFinite(p.mu), "mu should be finite");
        assertTrue(Double.isFinite(p.alpha), "alpha should be finite");
        assertTrue(p.mu >= 0, "mu should be non-negative");
        assertTrue(p.alpha >= 0, "alpha should be non-negative");
        assertTrue(p.alpha < beta, "alpha should stay below beta for stationarity");
    }

    @Test
    public void emptyStreamReturnsZeroParameters() {
        HawkesIntensityCalculator calc = new HawkesIntensityCalculator(null, 1.0);
        HawkesIntensityCalculator.HawkesParameters p = calc.estimateParameters(Stream.empty());

        assertEquals(0.0, p.mu, 0.0);
        assertEquals(0.0, p.alpha, 0.0);
    }

    @Test
    public void singleEventIsIndeterminateButDoesNotThrow() {
        HawkesIntensityCalculator calc = new HawkesIntensityCalculator(null, 1.0);
        HawkesIntensityCalculator.HawkesParameters p =
                calc.estimateParameters(postsAtSeconds(Collections.singletonList(0L)));

        // A single event normalizes to t=0 (zero duration): alpha is indeterminable.
        assertEquals(0.0, p.alpha, 0.0);
        assertValid(p, 1.0);
    }

    @Test
    public void allIdenticalTimestampsDoNotThrow() {
        // Several events at the exact same instant -> all normalize to t=0, so T=0.
        // This previously fed a degenerate surface to the optimizer.
        double beta = 1.0;
        HawkesIntensityCalculator calc = new HawkesIntensityCalculator(null, beta);
        List<Long> sameInstant = Collections.nCopies(10, 0L);

        HawkesIntensityCalculator.HawkesParameters p =
                calc.estimateParameters(postsAtSeconds(sameInstant));

        assertValid(p, beta);
    }

    @Test
    public void twoEventsDoNotThrow() {
        double beta = 1.0;
        HawkesIntensityCalculator calc = new HawkesIntensityCalculator(null, beta);

        HawkesIntensityCalculator.HawkesParameters p =
                calc.estimateParameters(postsAtSeconds(java.util.Arrays.asList(0L, 3600L)));

        assertValid(p, beta);
    }

    @Test
    public void verySmallBetaDoesNotThrow() {
        // A tiny beta makes the alpha bound range (beta - 1e-9) very small, which used to
        // produce an oversized initial trust-region radius. Verify it now stays consistent.
        double beta = 1e-6;
        HawkesIntensityCalculator calc = new HawkesIntensityCalculator(null, beta);
        List<Long> offsets = LongStream.range(0, 20).map(i -> i * 600L).boxed()
                .collect(Collectors.toList());

        HawkesIntensityCalculator.HawkesParameters p =
                calc.estimateParameters(postsAtSeconds(offsets));

        assertValid(p, beta);
    }

    @Test
    public void lowVolumeAuthorWithSmallMuBoundDoesNotThrow() {
        // Few events spread over a long window -> small muUpperBound, so the {0.1, 0.1}
        // initial guess must be clamped inside the bounds rather than rejected.
        double beta = 0.05;
        HawkesIntensityCalculator calc = new HawkesIntensityCalculator(null, beta);
        // 3 events across ~30 days.
        List<Long> offsets = java.util.Arrays.asList(0L, 10L * 24 * 3600, 30L * 24 * 3600);

        HawkesIntensityCalculator.HawkesParameters p =
                calc.estimateParameters(postsAtSeconds(offsets));

        assertValid(p, beta);
    }
}
