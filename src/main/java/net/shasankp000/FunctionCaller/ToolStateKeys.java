package net.shasankp000.FunctionCaller;

import java.util.List;
import java.util.Map;

/**
 * Which {@code $stateKey}s each tool exposes, in two views that have genuinely
 * different audiences.
 *
 * <p>A single table used to serve both, which coupled them invisibly. Removing an
 * entry because the prompt should not advertise it also, silently, made the
 * runtime unable to parse that tool's output — {@link FunctionCallerV2}'s
 * {@code parseOutputValues} looks its keys up in the table before it will store
 * anything. Three tools (see {@link #parseable()}) were disabled that way.
 *
 * @see #advertised() what the prompt tells the LLM
 * @see #parseable() what the runtime can read back
 */
public final class ToolStateKeys {

    private ToolStateKeys() {
    }

    /**
     * Keys the prompt advertises, so the LLM can reference a previous step's
     * result as {@code $name}.
     *
     * <p><strong>Registered tools only.</strong> The prompt instructs the model to
     * use only the tools it is given; advertising a placeholder for a name the
     * model cannot call is noise, and at worst invites it to hallucinate that
     * name. {@code ActionPromptTest} pins the invariant.
     *
     * <p>Deliberately narrower than {@link #parseable()}: a tool can be parseable
     * without being advertised (because it is not callable), but nothing
     * advertised may be unparseable.
     */
    public static Map<String, List<String>> advertised() {
        return ADVERTISED;
    }

    /**
     * Keys the runtime knows how to extract from a tool's output text.
     *
     * <p>A superset of {@link #advertised()} — it also covers tools the model is
     * not offered but which the runtime can still be asked to run, e.g. by a
     * deterministic plan or a hand-written pipeline.
     *
     * <p>Order matters: {@code parseOutputValues} fills the values in the order
     * this lists them and only stores them when the counts match.
     */
    public static Map<String, List<String>> parseable() {
        return PARSEABLE;
    }

    private static final Map<String, List<String>> ADVERTISED = Map.ofEntries(
            Map.entry("detectBlocks", List.of("lastDetectedBlock.x", "lastDetectedBlock.y", "lastDetectedBlock.z")),
            Map.entry("goTo", List.of("botPosition.x", "botPosition.y", "botPosition.z")),
            Map.entry("turn", List.of("facing.direction", "facing.facing", "facing.axis")),
            Map.entry("look", List.of("facing.direction", "facing.facing", "facing.axis")),
            Map.entry("mineBlock", List.of("lastMineStatus")),
            Map.entry("getOxygenLevel", List.of("oxygenLevel")),
            Map.entry("getHungerLevel", List.of("hungerLevel")),
            Map.entry("getHealthLevel", List.of("healthLevel"))
    );

    private static final Map<String, List<String>> PARSEABLE = Map.ofEntries(
            Map.entry("detectBlocks", List.of("lastDetectedBlock.x", "lastDetectedBlock.y", "lastDetectedBlock.z")),
            Map.entry("goTo", List.of("botPosition.x", "botPosition.y", "botPosition.z")),
            Map.entry("chartPathToBlock", List.of("finalBlockPos.x", "finalBlockPos.y", "finalBlockPos.z")),
            Map.entry("faceBlock", List.of("facing.yaw", "facing.pitch")),
            Map.entry("faceEntity", List.of("facing.entityName")),
            Map.entry("turn", List.of("facing.direction", "facing.facing", "facing.axis")),
            Map.entry("look", List.of("facing.direction", "facing.facing", "facing.axis")),
            Map.entry("mineBlock", List.of("lastMineStatus")),
            Map.entry("getOxygenLevel", List.of("oxygenLevel")),
            Map.entry("getHungerLevel", List.of("hungerLevel")),
            Map.entry("getHealthLevel", List.of("healthLevel"))
    );
}
