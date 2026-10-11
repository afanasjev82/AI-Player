package net.shasankp000.FilingSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The chat endpoint and model live in {@code settings.json5}, which is edited by
 * hand and is not deployable. {@link ManualConfig#withOverride} is the rule that
 * lets {@code .env} choose a different chat backend — the reason it exists is
 * being able to run the chat off the local vLLM endpoint instead of
 * ollama:qwen3:8b.
 */
class ManualConfigChatOverrideTest {

    private static final String CONFIGURED_URL = "http://localhost:11434/v1";
    private static final String CONFIGURED_MODEL = "qwen3:8b";

    @Test
    void absentOverrideKeepsTheConfiguredValue() {
        assertEquals(CONFIGURED_URL, ManualConfig.withOverride(CONFIGURED_URL, null));
        assertEquals(CONFIGURED_MODEL, ManualConfig.withOverride(CONFIGURED_MODEL, null));
    }

    @Test
    void blankOverrideKeepsTheConfiguredValue() {
        // docker-compose always defines the variables. When .env leaves them
        // empty they arrive as empty or whitespace strings, which must mean
        // "not set" rather than "blank out the config".
        assertEquals(CONFIGURED_URL, ManualConfig.withOverride(CONFIGURED_URL, ""));
        assertEquals(CONFIGURED_URL, ManualConfig.withOverride(CONFIGURED_URL, "   "));
        assertEquals(CONFIGURED_MODEL, ManualConfig.withOverride(CONFIGURED_MODEL, "\t"));
    }

    @Test
    void presentOverrideWins() {
        assertEquals("http://host.docker.internal:8000/v1",
                ManualConfig.withOverride(CONFIGURED_URL, "http://host.docker.internal:8000/v1"));
        assertEquals("gemma-4-26b-it",
                ManualConfig.withOverride(CONFIGURED_MODEL, "gemma-4-26b-it"));
    }

    @Test
    void overrideIsTrimmed() {
        assertEquals("http://host.docker.internal:8000/v1",
                ManualConfig.withOverride(CONFIGURED_URL, "  http://host.docker.internal:8000/v1  "));
        assertEquals("gemma-4-26b-it",
                ManualConfig.withOverride(CONFIGURED_MODEL, " gemma-4-26b-it "));
    }
}
