package net.shasankp000.ServiceLLMClients;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the timeout behavior added to {@link GenericOpenAIClient}.
 *
 * <p>Regression target: a hung LLM backend previously blocked the caller (and,
 * transitively, the Minecraft server thread) forever, tripping the 60s server
 * watchdog and forcing a shutdown. The client must now return an error string
 * promptly when the endpoint hangs instead of blocking indefinitely.
 *
 * <p>This test uses a real {@link HttpServer} that never responds, plus a
 * second server that returns a well-formed OpenAI-compatible JSON body, so we
 * exercise both the timeout and the happy path without any network dependency.
 */
class GenericOpenAIClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private GenericOpenAIClient startClient(long responseDelayMs) throws IOException {
        return startClient(responseDelayMs, GenericOpenAIClient.DEFAULT_REQUEST_TIMEOUT);
    }

    private GenericOpenAIClient startClient(long responseDelayMs, java.time.Duration requestTimeout) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/v1/chat/completions", exchange -> {
            if (responseDelayMs > 0) {
                try {
                    Thread.sleep(responseDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            writeJson(exchange, 200,
                "{\"choices\":[{\"message\":{\"content\":\"REQUEST_ACTION\"}}]}");
        });

        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new GenericOpenAIClient("", "test-model", base, requestTimeout);
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void returnsContentForWellFormedResponse() throws IOException {
        GenericOpenAIClient client = startClient(0);

        String response = client.sendPrompt("system", "user prompt");
        assertEquals("REQUEST_ACTION", response);
    }

    @Test
    void doesNotBlockForeverWhenBackendHangs() throws IOException {
        // Client times out after 1s; backend sleeps 10s.
        GenericOpenAIClient client = startClient(10_000, java.time.Duration.ofSeconds(1));

        long start = System.currentTimeMillis();
        String response = client.sendPrompt("system", "user prompt");
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(response.startsWith("Error:"), "Expected an error string, got: " + response);
        assertTrue(elapsed < 5_000, "Client blocked for " + elapsed + "ms instead of timing out");
    }
}
