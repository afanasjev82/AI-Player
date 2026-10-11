package net.shasankp000.FunctionCaller;

import java.util.Map;

public class SharedStateUtils {

    /**
     * Gets a value from sharedState, attempting to parse strings to numbers if possible.
     * Returns null if key missing or unparseable.
     */
    public static Object getValue(Map<String, Object> state, String key) {
        if (!state.containsKey(key)) return null;
        Object val = state.get(key);
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e1) {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException e2) {
                    return s;  // Fallback to string
                }
            }
        }
        return val;  // Already non-string
    }

    /**
     * Sets a value in sharedState (accepts any Object).
     */
    public static void setValue(Map<String, Object> state, String key, Object value) {
        state.put(key, value);
    }

    /**
     * Resolves a placeholder key to a string (for params; uses toString() on non-strings).
     *
     * <p>Returns {@link PlaceholderResolver#UNRESOLVED} rather than a usable-looking
     * value when the key is missing, so callers can tell "no value" apart from a
     * real one. Silently substituting a default here is what used to send the bot
     * to the world origin.
     */
    public static String resolveAsString(Map<String, Object> state, String key) {
        Object val = getValue(state, key);
        if (val == null) return PlaceholderResolver.UNRESOLVED;
        return val.toString();
    }
}
