package net.shasankp000.FunctionCaller;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the fix for a silent, dangerous bug: an unresolvable {@code $placeholder}
 * used to resolve to the string {@code "0"}, so a step referencing bot state that
 * did not exist was executed as a walk to the world origin — the bot wandered off
 * with nothing in the log explaining why.
 */
class PlaceholderResolverTest {

    private static final Function<String, Object> NO_STATE = key -> null;

    @Test
    void plainValuePassesThroughUnchanged() {
        assertEquals("15", PlaceholderResolver.resolve("15", NO_STATE));
        assertEquals("oak_log", PlaceholderResolver.resolve("oak_log", NO_STATE));
        assertEquals("true", PlaceholderResolver.resolve("true", NO_STATE));
    }

    @Test
    void resolvablePlaceholderReturnsTheStateValue() {
        Function<String, Object> state = key -> switch (key) {
            case "lastDetectedBlock.x" -> 128;
            case "botPosition.y" -> "64";
            default -> null;
        };
        assertEquals("128", PlaceholderResolver.resolve("$lastDetectedBlock.x", state));
        assertEquals("64", PlaceholderResolver.resolve("$botPosition.y", state));
    }

    /**
     * The regression: this used to be {@code "0"}, which is a valid coordinate, so
     * nothing downstream could tell it apart from a genuine origin.
     */
    @Test
    void missingPlaceholderIsUnresolvedNotZero() {
        String resolved = PlaceholderResolver.resolve("$lastDetectedBlock.x", NO_STATE);

        assertEquals(PlaceholderResolver.UNRESOLVED, resolved);
        assertTrue(PlaceholderResolver.isUnresolved(resolved));
        assertFalse("0".equals(resolved), "must not look like a usable coordinate");
    }

    /**
     * An omitted parameter is NOT the same as a broken reference. {@code goTo}
     * routinely omits {@code sprint}; treating that as unresolved would refuse a
     * perfectly good walk.
     *
     * <p>It resolves to EMPTY rather than {@code "0"} so numeric callers fall back
     * to their own default. A zero looked usable: an omitted {@code radiusIncrement}
     * reached {@code searchBlocks} as 0 and its expanding-radius loop then spun
     * forever, because {@code radius += 0} never reaches {@code maxRadius}.
     */
    @Test
    void nullValueMeansAbsentNotUnresolved() {
        String resolved = PlaceholderResolver.resolve(null, NO_STATE);

        assertEquals(PlaceholderResolver.ABSENT, resolved);
        assertEquals("", resolved, "absent must not look like a usable number");
        assertFalse(PlaceholderResolver.isUnresolved(resolved));
        assertThrows(NumberFormatException.class, () -> Integer.parseInt(resolved),
                "absent must make numeric defaults kick in");
    }

    /** A key that exists but holds null is unresolved, not absent. */
    @Test
    void presentButNullKeyIsUnresolved() {
        assertTrue(PlaceholderResolver.isUnresolved(
                PlaceholderResolver.resolve("$k", key -> null)));
    }

    @Test
    void detectsWhichParametersAreUnresolved() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("targetX", PlaceholderResolver.UNRESOLVED);
        params.put("targetY", "64");
        params.put("targetZ", PlaceholderResolver.UNRESOLVED);

        assertEquals(java.util.List.of("targetX", "targetZ"),
                PlaceholderResolver.unresolvedNames(params));
        assertTrue(PlaceholderResolver.hasUnresolved(params));
    }

    @Test
    void fullyResolvedParametersAreSafeToRun() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("x", "128");
        params.put("y", "64");
        params.put("z", "-200");
        params.put("sprint", "true");

        assertTrue(PlaceholderResolver.unresolvedNames(params).isEmpty());
        assertFalse(PlaceholderResolver.hasUnresolved(params));
    }

    /** An omitted optional parameter must not block the step either. */
    @Test
    void absentParameterDoesNotMarkTheStepUnresolved() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("x", "1");
        params.put("y", "2");
        params.put("z", "3");
        params.put("sprint", PlaceholderResolver.ABSENT); // omitted, not broken

        assertFalse(PlaceholderResolver.hasUnresolved(params));
    }

    @Test
    void emptyAndNullMapsAreSafe() {
        assertTrue(PlaceholderResolver.unresolvedNames(null).isEmpty());
        assertTrue(PlaceholderResolver.unresolvedNames(Map.of()).isEmpty());
        assertFalse(PlaceholderResolver.hasUnresolved(null));
    }
}
