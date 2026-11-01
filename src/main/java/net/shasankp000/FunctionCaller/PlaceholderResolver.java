package net.shasankp000.FunctionCaller;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Resolves {@code $placeholder} pipeline parameters against the bot's shared
 * state, and makes "could not be resolved" a distinct, detectable outcome.
 *
 * <p>This exists because the old behaviour was silently dangerous. A missing key
 * resolved to the string {@code "0"}, so an LLM that referenced a state key the
 * runtime never publishes (for example {@code $lastDetectedBlock.x} after a
 * search that found nothing) had its step executed as a move to
 * <strong>(0, 0, 0)</strong> — the world origin. The bot walked off to nowhere,
 * and nothing about the log said why.
 *
 * <p>The runtime already had code intended to catch this: the pipeline loop
 * checks parameters for an {@code "__UNRESOLVED__"} sentinel and re-plans via the
 * LLM. That check was dead, because nothing ever produced the sentinel. Now
 * something does.
 *
 * <p>Pure by design — it takes the state lookup as a function rather than reading
 * {@code SharedStateUtils} directly, because {@link FunctionCallerV2} resolves
 * {@code AIPlayer.CONFIG} in its static initialiser and therefore cannot be
 * loaded in a test JVM at all.
 */
public final class PlaceholderResolver {

    /**
     * Marker for a placeholder that was requested but has no value. Distinct
     * from any legitimate resolved value, so callers can test for it cheaply.
     */
    public static final String UNRESOLVED = "__UNRESOLVED__";

    /**
     * Value used when a parameter was not supplied at all.
     *
     * <p>Empty, not {@code "0"}. A zero reads as a usable value: for
     * {@code searchBlocks} an omitted {@code radiusIncrement} became 0, and the
     * tool's {@code while (radius <= max) radius += increment} loop then spun
     * forever on a ForkJoinPool worker. Empty makes numeric callers fall back to
     * their own default instead of silently adopting zero.
     *
     * <p>Deliberately NOT {@link #UNRESOLVED} either: an omitted optional
     * parameter is normal and means "use the tool's own default". {@code goTo}
     * omits {@code sprint} routinely, and treating that as unresolved would
     * refuse a perfectly good walk. Only a {@code $placeholder} that cannot be
     * read is unresolved — absence is a different thing from a broken reference.
     */
    public static final String ABSENT = "";

    private PlaceholderResolver() {
    }

    /**
     * @param value  the raw parameter value, possibly {@code $some.state.key}
     * @param lookup resolves a state key to its value, or {@code null} if absent
     * @return the resolved value, {@link #UNRESOLVED} when the placeholder has no
     *         value, {@link #ABSENT} when {@code value} is null, otherwise
     *         {@code value} unchanged
     */
    public static String resolve(String value, Function<String, Object> lookup) {
        if (value == null) {
            return ABSENT;
        }
        if (!value.startsWith("$")) {
            return value;
        }
        Object resolved = lookup.apply(value.substring(1));
        return resolved == null ? UNRESOLVED : resolved.toString();
    }

    /**
     * Normalises a step's {@code parameters} array into a name→value map,
     * resolving {@code $placeholders} via {@code lookup}.
     *
     * <p>Accepts two shapes. The canonical one is the list of
     * {@code {"parameterName": …, "parameterValue": …}} pairs the prompt asks
     * for. But models frequently ignore that instruction and emit a single flat
     * object ({@code {"blockType": "oak_log", "initialRadius": 15}}), where each
     * key IS the parameter name and each value IS its value. The old code read
     * {@code param.get("parameterName").getAsString()} unconditionally, so the
     * flat form threw a {@link NullPointerException} inside the pipeline executor
     * — which is never awaited — and the whole pipeline silently stopped with
     * nothing in the log. Accepting both shapes removes that silent failure mode.
     *
     * @param parameters the {@code parameters} array from a pipeline step (or a
     *                   single-function {@code "parameters"} value); may be null
     * @param lookup     resolves a {@code $state.key} reference to its value
     * @return name→value map in iteration order, never null
     */
    public static Map<String, String> toParamMap(JsonArray parameters, Function<String, Object> lookup) {
        Map<String, String> out = new LinkedHashMap<>();
        if (parameters == null) {
            return out;
        }
        for (JsonElement param : parameters) {
            if (param == null || !param.isJsonObject()) {
                continue;
            }
            JsonObject obj = param.getAsJsonObject();
            if (obj.has("parameterName")) {
                String name = obj.get("parameterName").getAsString();
                String raw = obj.has("parameterValue") ? obj.get("parameterValue").getAsString() : ABSENT;
                out.put(name, resolve(raw, lookup));
            } else {
                for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                    JsonElement value = entry.getValue();
                    String raw;
                    if (value == null || value.isJsonNull()) {
                        raw = ABSENT;
                    } else if (value.isJsonPrimitive()) {
                        raw = value.getAsString();
                    } else {
                        raw = value.toString();
                    }
                    out.put(entry.getKey(), resolve(raw, lookup));
                }
            }
        }
        return out;
    }

    /** Whether {@code value} is the {@link #UNRESOLVED} marker. */
    public static boolean isUnresolved(String value) {
        return UNRESOLVED.equals(value);
    }

    /**
     * Names of the parameters holding an unresolved placeholder, in iteration
     * order. Empty when the step is safe to run.
     */
    public static List<String> unresolvedNames(Map<String, String> paramMap) {
        if (paramMap == null || paramMap.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> entry : paramMap.entrySet()) {
            if (isUnresolved(entry.getValue())) {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    /** Whether any parameter holds an unresolved placeholder. */
    public static boolean hasUnresolved(Map<String, String> paramMap) {
        return !unresolvedNames(paramMap).isEmpty();
    }
}
