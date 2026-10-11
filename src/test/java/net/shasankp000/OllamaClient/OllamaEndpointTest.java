package net.shasankp000.OllamaClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Ollama address used to be hard-coded in eight files. These tests pin the
 * resolution and normalisation rules that replaced them.
 */
class OllamaEndpointTest {

    private static final String PROPERTY = "aiplayer.ollamaBaseUrl";

    @AfterEach
    void clearOverride() {
        System.clearProperty(PROPERTY);
        OllamaEndpoint.reset();
    }

    @Test
    void explicitPropertyWins() {
        System.setProperty(PROPERTY, "http://ollama-host:11434");
        OllamaEndpoint.reset();
        assertEquals("http://ollama-host:11434", OllamaEndpoint.baseUrl());
    }

    @Test
    void stripsOpenAiCompatibleV1Suffix() {
        // customApiUrl is configured in its OpenAI-compatible form, but Ollama's
        // native /api/chat lives at the bare root.
        System.setProperty(PROPERTY, "http://localhost:11434/v1");
        OllamaEndpoint.reset();
        assertEquals("http://localhost:11434", OllamaEndpoint.baseUrl());
    }

    @Test
    void stripsTrailingSlashes() {
        System.setProperty(PROPERTY, "http://localhost:11434///");
        OllamaEndpoint.reset();
        assertEquals("http://localhost:11434", OllamaEndpoint.baseUrl());
    }

    @Test
    void stripsV1AfterTrailingSlash() {
        System.setProperty(PROPERTY, "http://localhost:11434/v1/");
        OllamaEndpoint.reset();
        assertEquals("http://localhost:11434", OllamaEndpoint.baseUrl());
    }

    @Test
    void slashedFormIsWhatOllama4jWants() {
        System.setProperty(PROPERTY, "http://localhost:11434");
        OllamaEndpoint.reset();
        assertEquals("http://localhost:11434/", OllamaEndpoint.baseUrlSlash());
    }

    @Test
    void defaultsWhenNothingIsConfigured() {
        OllamaEndpoint.reset();
        assertEquals(OllamaEndpoint.DEFAULT_BASE_URL, OllamaEndpoint.baseUrl());
    }

    @Test
    void recognisesOllamaEndpoints() {
        assertTrue(OllamaEndpoint.pointsAtOllama("http://localhost:11434/v1"));
        assertTrue(OllamaEndpoint.pointsAtOllama("http://ollama:11434"));
        assertTrue(OllamaEndpoint.pointsAtOllama("http://my-ollama-server:8080"));
        assertFalse(OllamaEndpoint.pointsAtOllama("https://api.deepseek.com"));
        assertFalse(OllamaEndpoint.pointsAtOllama("http://host.docker.internal:8000/v1"));
        assertFalse(OllamaEndpoint.pointsAtOllama(null));
        assertFalse(OllamaEndpoint.pointsAtOllama("   "));
    }
}
