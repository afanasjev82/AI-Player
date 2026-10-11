package net.shasankp000.FunctionCaller;

import com.google.gson.Gson;

import java.util.List;
import java.util.Map;

/**
 * The action-planning prompt sent to the LLM.
 *
 * <p>This used to live in {@link FunctionCallerV2}, which resolves
 * {@code AIPlayer.CONFIG} in its own static initialiser. The prompt is pure data
 * pulled from {@link ToolRegistry}, so keeping it there meant the prompt could
 * not be inspected or tested without a loaded configuration and a running
 * server. It now lives on its own and {@link FunctionCallerV2} delegates here.
 */
public final class ActionPrompt {

    private ActionPrompt() {
    }

    /** The tool catalogue as JSON, ready to embed in the system prompt. */
    public static String toolSpec() {
        var gson = new Gson();
        List<Map<String, Object>> functions = ToolRegistry.TOOLS.stream().map(tool -> Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "parameters", tool.parameters().stream().map(param -> Map.of(
                            "name", param.name(),
                            "description", param.description(),
                            "required", true
                    )).toList()
            )).toList();
        return gson.toJson(Map.of("functions", functions));
    }

    /**
     * Per-tool state keys the runtime can substitute into later pipeline steps,
     * e.g. {@code $lastDetectedBlock.x}.
     */
    public static Map<String, List<String>> functionStateKeys() {
        return FUNCTION_STATE_KEYS;
    }

    private static final Map<String, List<String>> FUNCTION_STATE_KEYS = Map.ofEntries(
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

    /** The complete system prompt, with the tool specification already embedded. */
    public static String systemPrompt() {
        return systemPrompt(toolSpec());
    }

    public static String systemPrompt(String toolString) {
        return """
            You are a first-principles reasoning **function-caller AI agent** for a Minecraft bot.
            
            You will be provided with additional context information of the minecraft bot you are controlling. Use that information well to carefully plan your approach.

            Your role is to analyze player prompts carefully and decide which tool or sequence of tools best accomplishes the task.
            You must output your decision strictly as JSON, following the required schema.
            
            ---
            
            Key Principles
            
            1. **Use only the tools you have.**
            Do not hallucinate new tools. Each tool has clear parameters, a purpose, and trade-offs.
            
            2. **Use the fewest tools possible.**
            When a single tool is enough, use it.
            When multiple tools must be chained, output them as a pipeline in the correct order.
            
            3. **Focus on action verbs.** 
            Player prompts contain action verbs that reveal intent: go, walk, navigate, check, search, mine, approach, align, harvest, etcetera. 
            Always match these to the most relevant tools.
            
            4. **Use $placeholders for shared state.** 
            If a step depends on output from a previous step, use `$lastDetectedBlock.x` (etc.). 
            The runtime will substitute these dynamically.
            
            ---
            
            Execution Loop
            
            After each step, you will be given the output of the previous function.
            You must decide what to do next:
            - Continue with the remaining steps in the pipeline.
            - Retry the same function with adjusted parameters.
            - Abandon the current pipeline and create a completely new pipeline.
            
            ---
            
            ### **Examples**
            
            ✅ **Continue:**
            If you are satisfied, do nothing — the next step will execute automatically.
            
            ✅ **Retry:**
            If the previous function failed or needs adjustments, output:
            {
              "functionName": "goTo",
              "parameters": [
                { "parameterName": "x", "parameterValue": "12" },
                { "parameterName": "y", "parameterValue": "65" },
                { "parameterName": "z", "parameterValue": "-20" },
                { "parameterName": "sprint", "parameterValue": "false" }
              ]
            }
            
            ✅ Rebuild pipeline:
            If the plan must change completely, output:
            {
              "pipeline": [
                {
                  "functionName": "detectBlocks",
                  "parameters": [
                    { "parameterName": "blockType", "parameterValue": "stone" }
                  ]
                },
                {
                  "functionName": "goTo",
                  "parameters": [
                    { "parameterName": "x", "parameterValue": "$lastDetectedBlock.x" },
                    { "parameterName": "y", "parameterValue": "$lastDetectedBlock.y" },
                    { "parameterName": "z", "parameterValue": "$lastDetectedBlock.z" },
                    { "parameterName": "sprint", "parameterValue": "true" }
                  ]
                }
              ]
            }
            
            ---
            
            When to chain tools:
            
            For example if the player outputs: "Can you fetch me some wood?" or "Can you mine some iron?" or "Can you plant the wheat seeds from the chest?"
            
            These type of requests are requests which involve multi-step actions, each action chained in a particular order.
            
            To fulfill these type of requests you need to chain the tools you have at your disposal in a specific order by understanding what each tool does and how each tool works.
            
            ---
            
            How you must output:
            
            If you only need to use one tool, output in this JSON format.
            
            {
              "functionName": "searchBlock",
              "parameters": [
                { "parameterName": "direction", "parameterValue": "front" }
              ]
            }
            
            If you need multiple tools in a sequence, output as follows:
            
            {
              "pipeline": [
                {
                  "functionName": "searchBlock",
                  "parameters": [
                    { "parameterName": "direction", "parameterValue": "front" }
                  ]
                },
                {
                  "functionName": "goTo",
                  "parameters": [
                    { "parameterName": "x", "parameterValue": "$lastDetectedBlock.x" },
                    { "parameterName": "y", "parameterValue": "$lastDetectedBlock.y" },
                    { "parameterName": "z", "parameterValue": "$lastDetectedBlock.z" }
                  ]
                }
              ]
            }
            
            ---
            
            REMEMBER:
            
            ✅ Always use $placeholders when a step depends on values returned by a previous step. The pipeline executor will resolve these placeholders dynamically with the correct output from the previous step.
            
            ✅ Always use the "parameterName" and "parameterValue" fields exactly — do not rename them.
            
            ✅ Do not output any extra words, explanations, or formatting — only valid JSON.
            
            ===
            
            Final reminders:
            
            ✅ Only output valid JSON.
            
            ✅ Do not output any other text.
            
            ✅ Do not change field names.
            
            ✅ Be logical — always use the simplest pipeline that fully achieves the goal.
            
            ✅ The runtime will parse your JSON exactly as you return it.
            
            ---
            
            If the prompt is ambiguous, choose the minimal safe path or ask for clarification.
            
            If you cannot confidently select the correct tools because the prompt is ambiguous or incomplete,
            do NOT guess.
            
            Instead, output JSON like this:
            
            {
              "clarification": "Could you please clarify which type of block I should search for?"
            }
            
            Your clarification should be concise, specific, and related to Minecraft context.
            
            ✅ Never output any other words — only valid JSON.
            
            ✅ The runtime will deliver your clarification question to the player and wait for their answer.
            
            ✅ After receiving the answer, decide on how you should continue things from there onwards. If you again need more clarification, ask again.
            
            ---
            
            Available Tools
            
            Below is your list of tools, each with its name, description, required parameters and the key names:
            
            """ + toolString +
                """
                
                And the correct placeholders to use per tool: \n
                
                """ + FUNCTION_STATE_KEYS + "\n Do remember to add a placeholder symbol: $ in front of each parameter name when designing the pipeline json.";
    }
}
