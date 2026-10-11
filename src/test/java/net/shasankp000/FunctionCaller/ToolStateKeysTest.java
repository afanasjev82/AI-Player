package net.shasankp000.FunctionCaller;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the relationship between the two views of the state-key tables.
 *
 * <p>They were one table serving two audiences, which coupled them invisibly:
 * dropping an entry so the prompt would not advertise an uncallable tool also
 * stopped the runtime from parsing that tool's output, with no test to catch it.
 */
class ToolStateKeysTest {

    @Test
    void advertisedKeysOnlyCoverRegisteredTools() {
        Set<String> registered = ToolRegistry.TOOLS.stream()
                .map(Tool::name)
                .collect(Collectors.toSet());

        for (String tool : ToolStateKeys.advertised().keySet()) {
            assertTrue(registered.contains(tool),
                    "prompt advertises state keys for unregistered tool '" + tool
                            + "', which the model cannot call");
        }
    }

    /**
     * The important one. Anything the prompt tells the model to reference must be
     * something the runtime can actually parse and store, or the model is being
     * invited to write a placeholder that can never resolve.
     */
    @Test
    void everyAdvertisedToolIsAlsoParseable() {
        Map<String, List<String>> advertised = ToolStateKeys.advertised();
        Map<String, List<String>> parseable = ToolStateKeys.parseable();

        for (String tool : advertised.keySet()) {
            assertTrue(parseable.containsKey(tool),
                    "prompt advertises '" + tool + "' but the runtime cannot parse its output");
        }
    }

    /** Parseable is the wider view: it may cover tools the prompt never offers. */
    @Test
    void parseableCoversToolsThePromptDoesNotAdvertise() {
        Set<String> advertised = ToolStateKeys.advertised().keySet();
        Set<String> parseable = ToolStateKeys.parseable().keySet();

        assertTrue(parseable.containsAll(advertised), "parseable must be a superset");
        assertFalse(parseable.equals(advertised),
                "parseable must be strictly wider, else the split is pointless");

        // The three this split was made to restore.
        assertTrue(parseable.contains("chartPathToBlock"));
        assertTrue(parseable.contains("faceBlock"));
        assertTrue(parseable.contains("faceEntity"));
    }

    /**
     * Both views must name the same keys for a tool they share, otherwise the
     * prompt would advertise a key the parser never fills — which is the exact
     * class of silent mismatch this split exists to prevent.
     */
    @Test
    void sharedToolsNameTheSameKeysInBothViews() {
        Map<String, List<String>> advertised = ToolStateKeys.advertised();
        Map<String, List<String>> parseable = ToolStateKeys.parseable();

        for (Map.Entry<String, List<String>> entry : advertised.entrySet()) {
            List<String> parsed = parseable.get(entry.getKey());
            assertTrue(parsed != null && parsed.containsAll(entry.getValue()),
                    "'" + entry.getKey() + "' advertises " + entry.getValue()
                            + " but the parser fills " + parsed);
        }
    }
}
