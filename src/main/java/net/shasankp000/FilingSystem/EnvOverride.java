package net.shasankp000.FilingSystem;

/**
 * Resolves a configured value against an optional environment override.
 *
 * <p>Exists as its own class for two reasons. It is pure, so it can be tested
 * without a running Minecraft client — {@link ManualConfig} cannot be loaded in a
 * test JVM at all, because its static initialiser resolves the config directory
 * through the Fabric launcher. And the blank case has a specific meaning worth
 * pinning down: docker-compose always defines these variables, so an {@code .env}
 * entry left empty arrives as an empty string and must mean "not set", not
 * "blank out the configured value".
 *
 * <p>Note: {@code WebSearch.AISearchConfig.firstNonBlank} is the same rule for the
 * search keys. Worth collapsing into one place if a third caller appears.
 */
public final class EnvOverride {

    private EnvOverride() {
    }

    /**
     * @param configured the value from the config file
     * @param override   the environment value, which may be null or blank
     * @return the trimmed override when present, otherwise {@code configured}
     */
    public static String resolve(String configured, String override) {
        if (override == null || override.isBlank()) {
            return configured;
        }
        return override.trim();
    }
}
