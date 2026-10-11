package net.shasankp000.FunctionCaller;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exports the real action-planning system prompt so the model benchmark in
 * {@code eval/llm/} measures the prompt the mod actually sends, instead of a
 * hand-written approximation of it.
 *
 * <p>Writes to {@code build/benchmark/action_prompt.txt} (build output, not
 * source). Override with {@code -Daiplayer.benchmark.promptOut=<path>}.
 */
class ActionPromptTest {

    private static final String OUTPUT_PROPERTY = "aiplayer.benchmark.promptOut";
    private static final String DEFAULT_OUTPUT = "build/benchmark/action_prompt.json";

    /** Matches {@code "functionName": "..."} inside the prompt's worked examples. */
    private static final Pattern FUNCTION_NAME_IN_EXAMPLE =
            Pattern.compile("\"functionName\"\\s*:\\s*\"([^\"]+)\"");

    @Test
    void promptEmbedsTheToolCatalogue() {
        String toolSpec = ActionPrompt.toolSpec();
        String prompt = ActionPrompt.systemPrompt();

        assertTrue(prompt.contains("function-caller AI agent"),
                "prompt should be the action-planning system prompt");
        assertTrue(prompt.contains(toolSpec),
                "prompt should embed the tool specification");
        assertTrue(prompt.contains("\"functionName\":"),
                "prompt should show the expected output schema");
        assertTrue(prompt.contains("$ lastDetectedBlock.x") || prompt.contains("lastDetectedBlock.x"),
                "prompt should document the placeholder syntax");
        assertFalse(toolSpec.isBlank(), "tool specification should not be empty");
    }

    @Test
    void everyToolHasPlaceholderGuidance() throws Exception {
        String toolSpec = ActionPrompt.toolSpec();
        String prompt = ActionPrompt.systemPrompt();

        for (String tool : ToolRegistry.TOOLS.stream().map(tool -> tool.name()).toList()) {
            assertTrue(toolSpec.contains("\"" + tool + "\""),
                    "tool specification should list " + tool);
        }
        // The state-key table is appended after the tool list; both halves must
        // survive extraction for the pipeline placeholders to resolve.
        assertTrue(prompt.contains("Do remember to add a placeholder symbol"),
                "prompt should explain the $ placeholder convention");
    }

    /**
     * The prompt tells the model to use only the tools it is given. Worked
     * examples that emit other names teach it to hallucinate: the example used
     * to call {@code searchBlock}, while the registered tool is
     * {@code searchBlocks}.
     */
    @Test
    void workedExamplesOnlyUseRegisteredTools() {
        String prompt = ActionPrompt.systemPrompt();
        Set<String> registered = ToolRegistry.TOOLS.stream()
                .map(Tool::name)
                .collect(Collectors.toSet());

        Matcher matcher = FUNCTION_NAME_IN_EXAMPLE.matcher(prompt);
        List<String> taught = new ArrayList<>();
        while (matcher.find()) {
            taught.add(matcher.group(1));
        }
        assertFalse(taught.isEmpty(), "prompt should still contain worked examples");
        for (String name : taught) {
            assertTrue(registered.contains(name),
                    "prompt example emits unregistered tool '" + name + "'");
        }
    }

    /**
     * Same contradiction in the placeholder table: it used to advertise
     * {@code chartPathToBlock}, {@code faceBlock} and {@code faceEntity}, none of
     * which are in the catalogue the model is shown.
     */
    @Test
    void placeholderTableOnlyCoversRegisteredTools() {
        Set<String> registered = ToolRegistry.TOOLS.stream()
                .map(Tool::name)
                .collect(Collectors.toSet());

        for (String tool : ActionPrompt.functionStateKeys().keySet()) {
            assertTrue(registered.contains(tool),
                    "placeholder table mentions unregistered tool '" + tool + "'");
        }
    }

    @Test
    void exportsThePromptForBenchmarking() throws Exception {
        Path output = Paths.get(System.getProperty(OUTPUT_PROPERTY, DEFAULT_OUTPUT));
        Files.createDirectories(output.getParent());

        // A JSON artifact rather than the raw prompt, so the benchmark can also
        // check which function names the model was actually offered.
        List<String> tools = ToolRegistry.TOOLS.stream().map(Tool::name).toList();
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("systemPrompt", ActionPrompt.systemPrompt());
        artifact.put("tools", tools);
        artifact.put("functionStateKeys", ActionPrompt.functionStateKeys());

        String json = new GsonBuilder().create().toJson(artifact);
        Files.writeString(output, json, StandardCharsets.UTF_8);

        assertTrue(Files.size(output) > 1000, "exported prompt should be substantial");
        assertTrue(tools.size() > 10, "expected the full tool catalogue");
    }
}
