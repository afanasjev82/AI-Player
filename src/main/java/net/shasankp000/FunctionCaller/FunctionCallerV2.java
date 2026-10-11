// Kudos to this guy, Matt Williams, https://www.youtube.com/watch?v=IdPdwQdM9lA, for opening my eyes on function calling.

package net.shasankp000.FunctionCaller;

import com.google.gson.*;

import com.google.gson.stream.JsonReader;

import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
import net.shasankp000.OllamaClient.OllamaEndpoint;

import io.github.amithkoujalgi.ollama4j.core.models.chat.*;

import java.io.IOException;
import java.io.StringReader;

import java.util.*;

import java.util.concurrent.*;

import java.time.format.DateTimeFormatter;

import java.time.LocalDateTime;

import java.util.regex.Matcher;

import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.shasankp000.AIPlayer;

import net.shasankp000.ChatUtils.ChatContextManager;

import net.shasankp000.ChatUtils.ChatUtils;

import net.shasankp000.Entity.EntityDetails;

import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.autonomous.BotStatusLabel;
import net.shasankp000.PlayerUtils.FoodConsumptionTool;

import net.shasankp000.GameAI.State;

import net.shasankp000.Database.SQLiteDB;

import net.shasankp000.Entity.AutoFaceEntity;

import net.shasankp000.Entity.LookController;

import net.shasankp000.Overlay.ThinkingStateManager;

import net.shasankp000.PathFinding.ChartPathToBlock;

import net.shasankp000.PathFinding.GoTo;
import net.shasankp000.PathFinding.NavigationService;

import net.shasankp000.PlayerUtils.*;
import net.shasankp000.FilingSystem.LLMClientFactory;
import net.shasankp000.ServiceLLMClients.LLMClient;

import net.shasankp000.WebSearch.WebSearchTool;

import net.shasankp000.GameAI.planner.*;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import static net.shasankp000.ChatUtils.Helper.JsonUtils.cleanJsonString;

public class FunctionCallerV2 {

    private static final Logger logger = LoggerFactory.getLogger("function-caller");

    private static CommandSourceStack botSource = null;

    private static final String DB_URL = "jdbc:sqlite:" + "./sqlite_databases/" + "memory_agent.db";

    private static final String host = OllamaEndpoint.baseUrlSlash();

    private static final OllamaAPI ollamaAPI = new OllamaAPI(host);

    private static volatile String functionOutput = null;

    /**
     * Last action result captured by {@link #getFunctionOutput}, kept even after
     * {@link #reportCompletion} clears {@link #functionOutput} for chat-relay
     * freshness. Verification reads this so a build/terraform result is judged
     * on its ACTUAL output rather than the null {@code reportCompletion} left
     * behind (which made every successful build fail as "verification failed:
     * null" and poisoned the learning reward).
     */
    private static volatile String lastFunctionOutput = null;

    /**
     * Set (from any thread) when a higher-priority task was injected into the
     * goal queue while a plan is executing. The plan's step loop checks this
     * between atomic steps and aborts, so the queue can run the higher-priority
     * task; the caller re-enqueues the interrupted goal. Cleared by the engine.
     */
    public static volatile boolean preemptionRequested = false;

    private static final ExecutorService executor = Executors.newFixedThreadPool(4);

    private static final Map<String, Object> sharedState = new ConcurrentHashMap<>();  // Updated to Map<String, Object>

    /** Thrown between plan steps to abort a plan for preemption (not a failure). */
    static final class PreemptionException extends RuntimeException {
        PreemptionException() {
            super("preempted by a higher-priority task");
        }
    }

    private static UUID playerUUID;

    private static final Pattern THINK_BLOCK = Pattern.compile("<think>([\\s\\S]*?)</think>", Pattern.DOTALL);

    private static final String selectedLM = AIPlayer.CONFIG.getOllamaModel();

    // Markov Planner components (initialized on first use)
    private static Planner planner = null;
    private static ActionLogWriter actionLogWriter = null;
    private static MarkovChain2 markovChain = null;
    private static final double SAFE_THRESHOLD = 50.0;

    // Hybrid Planner components (advanced goal-oriented planning)
    private static HybridPlanner hybridPlanner = null;
    private static boolean useHybridPlanner = true; // Toggle between planners

    public FunctionCallerV2(CommandSourceStack botSource, UUID playerUUID) {
        FunctionCallerV2.botSource = botSource;
        ollamaAPI.setRequestTimeoutSeconds(90);
        FunctionCallerV2.playerUUID = playerUUID;
    }

    /**
     * Initialize the Markov planner system.
     * Should be called once during bot initialization.
     */
    public static void initializePlanner(ServerPlayer bot, net.shasankp000.GameAI.RLAgent rlAgent) {
        if (markovChain == null) {
            logger.info("[planner] Initializing Markov-based planner system...");

            // Ensure ActionRegistry is populated from ToolRegistry
            ActionRegistry.refreshFromToolRegistry();

            markovChain = new MarkovChain2();
            planner = new Planner(markovChain, rlAgent);
            actionLogWriter = new ActionLogWriter(markovChain, bot);
            logger.info("[planner] ✓ Planner system initialized");
        }

        // Initialize hybrid planner if enabled
        if (useHybridPlanner && hybridPlanner == null) {
            try {
                logger.info("[hybrid-planner] Initializing hybrid goal-oriented planner...");

                // Initialize goal vector system (uses simple TF-IDF-like approach)
                GoalVector goalVector = new GoalVector();

                // Build action graph from ActionRegistry
                ActionGraph actionGraph = new ActionGraph(goalVector);
                actionGraph.buildFromRegistry();

                // Initialize hybrid planner
                SequenceRiskAnalyzer riskAnalyzer = new SequenceRiskAnalyzer(rlAgent);
                hybridPlanner = new HybridPlanner(actionGraph, goalVector, markovChain, rlAgent, riskAnalyzer);

                logger.info("[hybrid-planner] ✓ Hybrid planner system initialized");
            } catch (Exception e) {
                logger.error("[hybrid-planner] Failed to initialize hybrid planner, falling back to Markov", e);
                useHybridPlanner = false;
            }
        }
    }

    /**
     * Handle a natural language goal using the Markov planner.
     * Falls back to LLM-based planning if the Markov planner fails.
     *
     * @param naturalLanguageGoal User's goal in natural language (e.g., "get some wood")
     * @param currentState Current bot state
     * @param bot Bot entity
     * @param rlAgent RL agent for state management
     * @param botSource Bot's command source (required for function execution)
     * @return CompletableFuture that completes when plan execution finishes
     */
    public static CompletableFuture<Boolean> handleUserGoal(
            String naturalLanguageGoal,
            State currentState,
            ServerPlayer bot,
            net.shasankp000.GameAI.RLAgent rlAgent,
            CommandSourceStack botSource) {

        // ✅ Store botSource for function execution
        FunctionCallerV2.botSource = botSource;

        // Ensure planner is initialized
        if (planner == null) {
            initializePlanner(bot, rlAgent);
        }

        // Parse goal using GoalMapper
        short goalId = GoalMapper.parseGoal(naturalLanguageGoal);

        if (goalId == 0) {
            logger.warn("[planner] Unknown goal: '{}', falling back to LLM", naturalLanguageGoal);
            return fallbackToLLM(naturalLanguageGoal, currentState);
        }

        String goalName = GoalMapper.getGoalName(goalId);
        logger.info("[planner] Parsed goal '{}' → ID {} ({})", naturalLanguageGoal, goalId, goalName);

        Plan plan = null;

        // Try hybrid planner first if enabled
        if (useHybridPlanner && hybridPlanner != null) {
            try {
                logger.info("[hybrid-planner] Attempting hybrid planning for goal '{}'", goalName);
                plan = hybridPlanner.buildPlan(currentState, naturalLanguageGoal, goalId);

                if (plan != null) {
                    logger.info("[hybrid-planner] ✓ Using hybrid planner (score: {})", plan.getTotalScore());
                    return executePlan(plan, actionLogWriter, currentState);
                }
            } catch (Exception e) {
                logger.error("[hybrid-planner] Hybrid planning failed, falling back to Markov", e);
            }
        }

        // Fall back to Markov planner
        if (plan == null) {
            logger.info("[planner] Attempting Markov planning for goal '{}'", goalName);
            plan = planner.buildPlan(currentState, goalId);
        }

        if (plan != null && plan.getTotalScore() < SAFE_THRESHOLD * 4) {
            logger.info("[planner] ✓ Using Markov planner for goal '{}' (score: {})",
                    goalName, plan.getTotalScore());
            return executePlan(plan, actionLogWriter, currentState);
        } else {
            if (plan == null) {
                logger.warn("[planner] All planners returned null for goal '{}', falling back to LLM", goalName);
            } else {
                logger.warn("[planner] Planner score too high ({}) for goal '{}', falling back to LLM",
                        plan.getTotalScore(), goalName);
            }
            return fallbackToLLM(naturalLanguageGoal, currentState);
        }
    }

    /**
     * Fallback to LLM-based planning when Markov planner fails.
     * Generates pipeline using language model and executes it.
     */
    private static CompletableFuture<Boolean> fallbackToLLM(String goal, State currentState) {
        logger.info("[planner] 🤖 LLM fallback for goal: '{}'", goal);

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Build context for LLM
                InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1);
                map.updateMap();
                Map<String, String> surroundingsStr = map.summarizeSurroundings();
                Map<String, Object> surroundings = new HashMap<>();
                surroundings.putAll(surroundingsStr);

                String botContext = buildLLMBotContext(currentState, sharedState, surroundings);
                String systemPrompt = buildPrompt(toolBuilder());
                String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;

                // Construct user prompt
                String userPrompt = "Goal: " + goal + "\n\n" +
                    "The automated planning systems (Hybrid and Markov) were unable to generate a safe plan for this goal. " +
                    "Please analyze the situation and generate an appropriate action pipeline. " +
                    "Consider the bot's current state and surroundings carefully.";

                String llmProvider = System.getProperty("aiplayer.llmMode", "custom");
                LLMClient client = LLMClientFactory.createClient(llmProvider);
                if (client == null) {
                    logger.error("[planner] No OpenAI-compatible LLM client configured for fallback.");
                    return false;
                }

                String response = client.sendPrompt(fullSystemPrompt, userPrompt);

                logger.info("[planner] Raw LLM response: {}", response);

                // Parse and execute LLM response
                String cleanedResponse = stripThinkBlock(response);
                String jsonPart = extractJson(cleanedResponse);
                logger.info("[planner] Extracted JSON: {}", jsonPart);

                JsonObject jsonObject = JsonParser.parseString(jsonPart).getAsJsonObject();

                if (jsonObject.has("pipeline")) {
                    JsonArray pipeline = jsonObject.getAsJsonArray("pipeline");
                    logger.info("[planner] ✓ LLM generated pipeline with {} steps", pipeline.size());

                    // Execute pipeline using existing runPipelineLoop
                    runPipelineLoop(pipeline);
                    return true;

                } else if (jsonObject.has("functionName")) {
                    // Single function call
                    String fnName = jsonObject.get("functionName").getAsString();
                    JsonArray paramsArray = jsonObject.getAsJsonArray("parameters");
                    Map<String, String> paramMap = new HashMap<>();

                    for (JsonElement parameter : paramsArray) {
                        JsonObject paramObj = parameter.getAsJsonObject();
                        String paramName = paramObj.get("parameterName").getAsString();
                        String paramValue = resolvePlaceholder(
                            paramObj.get("parameterValue").getAsString(),
                            sharedState
                        );
                        paramMap.put(paramName, paramValue);
                    }

                    logger.info("[planner] ✓ LLM generated single function: {} with {}", fnName, paramMap);
                    callFunction(fnName, paramMap, sharedState).join();
                    return true;

                } else if (jsonObject.has("clarification")) {
                    String clarification = jsonObject.get("clarification").getAsString();
                    logger.warn("[planner] ⚠ LLM requested clarification: {}", clarification);
                    ChatContextManager.setPendingClarification(
                        playerUUID,
                        goal,
                        clarification,
                        botSource.getTextName()
                    );
                    sendMessageToPlayer(clarification);
                    return false;
                } else {
                    logger.error("[planner] ❌ Invalid LLM response format");
                    return false;
                }

            } catch (Exception e) {
                logger.error("[planner] ❌ LLM fallback error: {}", e.getMessage(), e);
                return false;
            }
        });
    }

    private static class ExecutionRecord {
        String timestamp;
        String command;
        List<Double> eventEmbedding;
        List<Double> eventContextEmbedding;
        List<Double> eventResultEmbedding;
        String result;
        String context;

        private ExecutionRecord(String Timestamp, String command, String context, String result, List<Double> eventEmbedding, List<Double> eventContextEmbedding, List<Double> eventResultEmbedding) {
            this.context = context;
            this.timestamp = Timestamp;
            this.command = command;
            this.eventEmbedding = eventEmbedding;
            this.eventContextEmbedding = eventContextEmbedding;
            this.eventResultEmbedding = eventResultEmbedding;
            this.result = result;
        }

        private void updateRecords() {
            try {
                // Store command as memory with prompt=command, response=result
                SQLiteDB.storeMemory("function_call", this.command, this.result, this.eventEmbedding);
            } catch (Exception e) {
                logger.error("Caught exception: {} ", (Object) e.getStackTrace());
                throw new RuntimeException(e);
            }
        }
    }

    private static String getCurrentDateandTime() {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
        LocalDateTime now = LocalDateTime.now();
        return dtf.format(now);
    }

    private static void getFunctionOutput(String method) {
        functionOutput = String.valueOf(method);
        lastFunctionOutput = functionOutput;
    }

    /**
     * Relay a completed function call back to chat so the requester sees the
     * outcome. Reads the per-action {@code functionOutput} set by
     * {@link #getFunctionOutput} (e.g. "✅ Crafted 1× stone_pickaxe.") and falls
     * back to a generic confirmation for functions that report nothing. Clears
     * {@code functionOutput} so the next action never relays a stale result.
     */
    private static void reportCompletion(String functionName, Map<String, String> paramMap) {
        String result = functionOutput;
        functionOutput = null;
        String item = paramMap.get("item");
        String message;
        if (result != null && !result.isBlank()) {
            message = result;
        } else if (item != null && !item.isBlank()) {
            message = "✅ " + functionName + " " + item + " completed";
        } else {
            message = "✅ " + functionName + " completed";
        }
        logger.info(message);
        if (botSource != null) {
            ChatUtils.sendChatMessages(botSource, message);
        }
    }

    private static class Tools {
        /** goTo tool: path finder + tracer **/
        private static void goTo(int x, int y, int z, boolean sprint) {
            System.out.println("Going to coordinates: " + x + ", " + y + ", " + z + " | Sprint: " + sprint);
            if (botSource == null) {
                System.out.println("Bot not found.");
                getFunctionOutput("Bot not found.");
                return;
            }
            try {
                // ✅ Call the method and wait for result
                String result = GoTo.goTo(botSource, x, y, z, sprint);
                // ✅ Ensure we have a valid result for parsing
                if (result == null || result.trim().isEmpty()) {
                    // Fallback: get current bot position
                    ServerPlayer bot = botSource.getPlayer();
                    if (bot != null) {
                        BlockPos pos = bot.blockPosition();
                        result = String.format("Bot position - x: %d y: %d z: %d",
                                pos.getX(), pos.getY(), pos.getZ());
                    }
                }
                getFunctionOutput(result);
            } catch (Exception e) {
                logger.error("Error in goTo: ", e);
                getFunctionOutput("Failed to navigate to coordinates: " + e.getMessage());
            }
        }

        /** chartPathToBlock: final positioning **/
        private static void chartPathToBlock(int targetX, int targetY, int targetZ, String blockType) {
            System.out.println("Charting path to block at: " + targetX + ", " + targetY + ", " + targetZ + " | BlockType: " + blockType);
            getFunctionOutput(ChartPathToBlock.chart(Objects.requireNonNull(botSource.getPlayer()), new BlockPos(targetX, targetY, targetZ), blockType));
        }

        /** faceBlock: look at block **/
        private static void faceBlock(int targetX, int targetY, int targetZ) {
            System.out.println("Facing block at: " + targetX + ", " + targetY + ", " + targetZ);
            getFunctionOutput(LookController.faceBlock(Objects.requireNonNull(botSource.getPlayer()), new BlockPos(targetX, targetY, targetZ)));
        }

        /** faceEntity: look at entity **/
        private static void faceEntity(int targetX, int targetY, int targetZ) {
            System.out.println("Facing entity at: " + targetX + ", " + targetY + ", " + targetZ);
            ServerPlayer bot = Objects.requireNonNull(botSource.getPlayer());
            // Get the world
            var world = bot.level();
            // Create a small bounding box around the coordinates to find nearby entities
            var box = new AABB(
                    targetX - 1, targetY - 1, targetZ - 1,
                    targetX + 1, targetY + 1, targetZ + 1
            );
            // Get all entities except the bot itself
            var entities = world.getEntities(bot, box);
            if (entities.isEmpty()) {
                System.out.println("No entity found at given coordinates.");
                getFunctionOutput("No entity found at given coordinates.");
                return;
            }
            // Take the first entity found
            var target = entities.getFirst();
            LookController.faceEntity(bot, target);
            getFunctionOutput("Facing entity: " + target.getName().getString());
        }

        /** detectBlocks: raycast with block type filter **/
        private static void detectBlocks(String blockType) {
            System.out.println("Detecting blocks of type: " + blockType);
            if (botSource == null || botSource.getPlayer() == null) {
                getFunctionOutput("Bot not found.");
                return;
            }
            try {
                BlockPos outputPos = blockDetectionUnit.detectBlocks(
                        Objects.requireNonNull(botSource.getPlayer()), blockType);
                String output;
                if (outputPos == null) {
                    output = "Block not found!";
                } else {
                    output = "Block found at " + outputPos.getX() + " " + outputPos.getY() + " " + outputPos.getZ();
                }
                getFunctionOutput(output);
            } catch (Exception e) {
                logger.error("Error in detectBlocks: ", e);
                getFunctionOutput("Failed to detect blocks: " + e.getMessage());
            }
        }

        /** turn: change torso facing direction **/
        private static void turn(String direction) {
            System.out.println("Turning to: " + direction);
            MinecraftServer server = botSource.getServer();
            String botName = botSource.getTextName();
            server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " turn " + direction); // choosing the command route instead of calling function to check if the bug still exists.
            getFunctionOutput("Now facing " + direction + " which is in " + Objects.requireNonNull(botSource.getPlayer()).getNearestViewDirection().getName() + " in " + Objects.requireNonNull(botSource.getPlayer()).getNearestViewDirection().getAxis().getSerializedName() + " axis.");
        }

        /** look: change head facing direction **/
        private static void look(String cardinalDirection) {
            System.out.println("Looking at: " + cardinalDirection);
            MinecraftServer server = botSource.getServer();
            String botName = botSource.getTextName();
            server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " look " + cardinalDirection); // choosing the command route instead of calling function to check if the bug still exists.
            getFunctionOutput("Now facing cardinal direction: " + Objects.requireNonNull(botSource.getPlayer()).getNearestViewDirection().getName() + " which is in " + Objects.requireNonNull(botSource.getPlayer()).getNearestViewDirection().getAxis().getSerializedName() + " axis.");
        }

        /** placeBlock: place block at coordinates **/
        private static void placeBlock(int targetX, int targetY, int targetZ, String blockType) {
            System.out.println("Placing block " + blockType + " at: " + targetX + ", " + targetY + ", " + targetZ);
            if (botSource == null || botSource.getPlayer() == null) {
                getFunctionOutput("Bot not found.");
                return;
            }
            try {
                // ✅ Use CompletableFuture.get() to wait for completion
                CompletableFuture<String> placementFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return BlockPlacementTool.placeBlock(
                                Objects.requireNonNull(botSource.getPlayer()),
                                new BlockPos(targetX, targetY, targetZ),
                                blockType
                        ).get();
                    } catch (Exception e) {
                        logger.error("Failed to place block: {}", e.getMessage(), e);
                        return "⚠️ Failed to place block: " + e.getMessage();
                    }
                });
                // Wait for result with timeout
                String result = placementFuture.get(10, TimeUnit.SECONDS);
                getFunctionOutput(result);
            } catch (Exception e) {
                logger.error("Error in placeBlock: ", e);
                getFunctionOutput("⚠️ Failed to place block: " + e.getMessage());
            }
        }

        /** getOxygenLevel: report air level **/
        private static void getOxygenLevel() {
            System.out.println("Getting oxygen level...");
            getFunctionOutput("Oxygen Level: " + getPlayerOxygen.getBotOxygenLevel(Objects.requireNonNull(botSource.getPlayer())));
        }

        /** getHungerLevel: report hunger **/
        private static void getHungerLevel() {
            System.out.println("Getting hunger level...");
            getFunctionOutput("Hunger Level: " + getPlayerHunger.getBotHungerLevel(Objects.requireNonNull(botSource.getPlayer())));
        }

        /** getHealthLevel: report health **/
        private static void getHealthLevel() {
            System.out.println("Getting health level...");
            getFunctionOutput("Remaining hearts: " + getHealth.getBotHealthLevel(Objects.requireNonNull(botSource.getPlayer())));
        }

        /** equipArmor: equip the best armor available in inventory **/
        private static void equipArmor() {
            if (botSource == null || botSource.getPlayer() == null) {
                getFunctionOutput("Bot not found.");
                return;
            }

            ServerPlayer bot = botSource.getPlayer();
            MinecraftServer server = botSource.getServer();
            CompletableFuture<Integer> equippedCountFuture = new CompletableFuture<>();
            Runnable equipAction = () -> {
                try {
                    armorUtils.autoEquipArmor(bot);
                    int equippedCount = 0;
                    for (var slot : new net.minecraft.world.entity.EquipmentSlot[]{
                            net.minecraft.world.entity.EquipmentSlot.HEAD,
                            net.minecraft.world.entity.EquipmentSlot.CHEST,
                            net.minecraft.world.entity.EquipmentSlot.LEGS,
                            net.minecraft.world.entity.EquipmentSlot.FEET}) {
                        if (!bot.getItemBySlot(slot).isEmpty()) equippedCount++;
                    }
                    equippedCountFuture.complete(equippedCount);
                } catch (Exception e) {
                    equippedCountFuture.completeExceptionally(e);
                }
            };

            try {
                if (server.isSameThread()) {
                    equipAction.run();
                } else {
                    server.execute(equipAction);
                }
                int equippedCount = equippedCountFuture.get(5, TimeUnit.SECONDS);
                if (equippedCount > 0) {
                    getFunctionOutput("Equipped best available armor; " + equippedCount + " armor piece(s) equipped.");
                } else {
                    getFunctionOutput("No armor was available to equip.");
                }
            } catch (Exception e) {
                logger.error("Failed to equip armor", e);
                getFunctionOutput("Failed to equip armor: " + e.getMessage());
            }
        }

        private static void webSearch(String query) {
            System.out.println("Running web search...");
            getFunctionOutput("Web search result: " + WebSearchTool.search(query));
        }

        /** searchBlocks: efficiently search for blocks in expanding radius **/
        private static void searchBlocks(String blockType, int initialRadius, int maxRadius, int radiusIncrement) {
            System.out.println("Searching for " + blockType + " in radius " + initialRadius + "-" + maxRadius);
            if (botSource == null || botSource.getPlayer() == null) {
                getFunctionOutput("Bot not found.");
                return;
            }
            try {
                ServerPlayer bot = botSource.getPlayer();
                BlockPos result = net.shasankp000.Tools.SearchBlocks.searchBlock(
                        bot,
                        blockType,
                        initialRadius,
                        maxRadius,
                        radiusIncrement
                );

                if (result != null) {
                    getFunctionOutput(String.format("Found %s at x: %d y: %d z: %d (distance: %d blocks)",
                            blockType,
                            result.getX(),
                            result.getY(),
                            result.getZ(),
                            bot.blockPosition().distManhattan(result)));
                } else {
                    getFunctionOutput(String.format("No %s found within %d blocks", blockType, maxRadius));
                }
            } catch (Exception e) {
                logger.error("Error in searchBlocks: ", e);
                getFunctionOutput("Failed to search for blocks: " + e.getMessage());
            }
        }

        private static void sendMessageToChat(String message) {
            System.out.println("Sending message to chat...");
            ChatUtils.sendChatMessages(botSource, message);
        }
    }

    private static String toolBuilder() {
        return ActionPrompt.toolSpec();
    }

    private static String buildPrompt(String toolString) {
        return ActionPrompt.systemPrompt(toolString);
    }

    private static String generatePromptContext(String userPrompt) {
        String contextOutput = "";
        String sysPrompt = """
        You are a context generation AI agent in terms of minecraft. \n
        This means that you will have a prompt from a user, who is the player and you need to analyze the context of the player's prompts, i.e what the player means by the prompt. \n
        This context information will then be used by a minecraft bot to understand what the user is trying to say. \n
        \n
        Here are some example player prompts you may receive: \n
        1. Could you check if there is a block in front of you? \n
        2. Look around for any hostile mobs, and report to me if you find any. \n
        3. Could you mine some stone and bring them to me? \n
        4. Craft a set of iron armor. \n
        5. Please go to coordinates 10 -60 20. \n
        \n
        A few more variations of the prompts may be: \n
        "Could you search for blocks in front of you?"
        "Do you see if there is a block in front of you?"
        "Can you mine some stone and bring them to me?
        "Please move to 10 -60 20." or "Please go to the coords 10 -60 20" or "Please go to 10 -60 20" and so on... \n
        \n
        Here are some examples of the format in which you MUST answer.
        \n
        1. The player asked you to check whether there is a block in front of you or not. \n
        2. The player asked you to search for hostile mobs around you, and to report to the player if you find any such hostile mob. \n
        3. The player asked you to mine some stone and then bring the stone to the player. \n
        4. The player asked you to craft a set of iron armor. \n
        5. The player asked you to go to coordinates 10 -60 20. You followed the instructions and began movement to the coordinates. \n
        Remember that all the context you generate should be in the past tense, sense it is being recorded after the deed has been done.
        \n
        "Remember that when dealing with prompts that ask the bot to go to a specific set of x y z coordinates, you MUST NOT alter the coordinates, they SHOULD BE the exact same as in the prompt given by the player.
        \n
        Now,remember that you must only generate the context as stated in the examples, nothing more, nothing less. DO NOT add your own opinions/statements/thinking to the context you generate. \n
        Remember that if you generate incorrect context then the bot will not be able to understand what the user has asked of it.
        \s
        \s""";
        try {
            List<OllamaChatMessage> messages = new java.util.ArrayList<>();
            messages.add(new OllamaChatMessage(OllamaChatMessageRole.SYSTEM, sysPrompt));
            messages.add(new OllamaChatMessage(OllamaChatMessageRole.USER, "Player prompt: " + userPrompt));

            net.shasankp000.OllamaClient.OllamaThinkingResponse thinkingResponse =
                    net.shasankp000.OllamaClient.OllamaAPIHelper.smartChat(
                            ollamaAPI,
                            OllamaEndpoint.baseUrl(),
                            net.shasankp000.AIPlayer.CONFIG.getOllamaModel(),
                            messages
                    );

            contextOutput = thinkingResponse.getContent();
        } catch (IOException | InterruptedException | JsonSyntaxException e) {
            logger.error("{}", (Object) e.getStackTrace());
        }
        return contextOutput;
    }

    public static String buildLLMBotContext(State state, Map<String, Object> sharedState, Map<String, Object> surroundingsSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bot current state:\n");
        sb.append("- Position: (").append(state.getBotX()).append(", ").append(state.getBotY()).append(", ").append(state.getBotZ()).append(")\n");

        Object direction = SharedStateUtils.getValue(sharedState, "facing.direction");
        if (direction != null) {
            sb.append("- Facing: ").append(direction);
            Object facing = SharedStateUtils.getValue(sharedState, "facing.facing");
            if (facing != null) {
                sb.append(" (").append(facing);
                Object axis = SharedStateUtils.getValue(sharedState, "facing.axis");
                if (axis != null) {
                    sb.append(", axis: ").append(axis);
                }
                sb.append(")");
            }
            sb.append("\n");
        } else {
            // first time call.
            assert botSource.getPlayer() != null;
            Direction facingDir = botSource.getPlayer().getNearestViewDirection();
            sb.append("- Facing: ").append(facingDir.getName());
            sb.append(" (axis: ").append(facingDir.getAxis().getSerializedName()).append(")");
        }

        sb.append("- Selected Item: ").append(state.getSelectedItem()).append("\n");
        if (!state.getNearbyBlocks().isEmpty()) {
            sb.append("- Nearby Blocks: ").append(state.getNearbyBlocks().stream().limit(3).toList()).append("\n");
        }

        if (!state.getNearbyEntities().isEmpty()) {
            List<String> nearbyHostiles = state.getNearbyEntities().stream()
                    .filter(EntityDetails::isHostile)
                    .map(EntityDetails::getName)
                    .limit(2)
                    .toList();
            if (!nearbyHostiles.isEmpty()) {
                sb.append("- Nearby Hostile Entities: ").append(nearbyHostiles).append("\n");
            }
        }

        sb.append("- Health: ").append(state.getBotHealth()).append("\n");
        sb.append("- Hunger: ").append(state.getBotHungerLevel()).append("\n");
        sb.append("- Oxygen: ").append(state.getBotOxygenLevel()).append("\n");
        if (state.getDistanceToDangerZone() > 0) {
            sb.append("- Bot is close to a danger zone\n");
        }

        // Inject summarized surroundings
        if (surroundingsSummary != null && !surroundingsSummary.isEmpty()) {
            sb.append("- Immediate surroundings:\n");
            for (Map.Entry<String, Object> entry : surroundingsSummary.entrySet()) {
                sb.append(" • ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        return sb.toString();
    }

    public static void run(String userPrompt) {
        ollamaAPI.setRequestTimeoutSeconds(600);
        String systemPrompt = FunctionCallerV2.buildPrompt(toolBuilder());
        String response;
        State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
        InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1); // 1-block radius in all directions
        map.updateMap();
        // If method returns Map<String, String>
        Map<String, String> surroundingsStr = map.summarizeSurroundings();
        Map<String, Object> surroundings = new HashMap<>(surroundingsStr);

        String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
        String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;
        try {
            List<OllamaChatMessage> messages = new java.util.ArrayList<>();
            messages.add(new OllamaChatMessage(OllamaChatMessageRole.SYSTEM, fullSystemPrompt));
            messages.add(new OllamaChatMessage(OllamaChatMessageRole.USER, userPrompt));

            net.shasankp000.OllamaClient.OllamaThinkingResponse thinkingResponse =
                    net.shasankp000.OllamaClient.OllamaAPIHelper.smartChat(
                            ollamaAPI,
                            OllamaEndpoint.baseUrl(),
                            net.shasankp000.AIPlayer.CONFIG.getOllamaModel(),
                            messages
                    );

            response = thinkingResponse.getContent();
            logger.info("Raw LLM Response: {}", response);
            String cleanedResponse = stripThinkBlock(response);
            String jsonPart = extractJson(cleanedResponse);
            logger.info("Extracted JSON: {}", jsonPart);
            executeFunction(userPrompt, jsonPart);
        } catch (Exception e) {
            logger.error("Error in Function Caller: {}", e.getMessage());
        }
    }

    public static void run(String userPrompt, LLMClient client) {
        String systemPrompt = FunctionCallerV2.buildPrompt(toolBuilder());
        String response;
        State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
        InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1); // 1-block radius in all directions
        map.updateMap();
        // If method returns Map<String, String>
        Map<String, String> surroundingsStr = map.summarizeSurroundings();
        Map<String, Object> surroundings = new HashMap<>();
        surroundings.putAll(surroundingsStr);

        String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
        String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;
        try {
            response = sendForAction(client, fullSystemPrompt, userPrompt);
            logger.info("Raw LLM Response: {}", response);
            if (response == null || response.isBlank() || response.startsWith("Error:")) {
                logger.error("Function caller provider returned no usable response: {}", response);
                sendMessageToPlayer("I couldn't plan that action because the AI provider returned an empty response. Please try another model.");
                return;
            }
            String cleanedResponse = stripThinkBlock(response);
            String jsonPart = extractJson(cleanedResponse);
            logger.info("Extracted JSON: {}", jsonPart);
            executeFunction(userPrompt, jsonPart, client);
        } catch (Exception e) {
            logger.error("Error in Function Caller: {}", e.getMessage(), e);
        }
    }

    /**
     * Send the action-planning prompt to the LLM. For a local Ollama endpoint
     * (the "custom" provider pointed at :11434) this routes through the NATIVE
     * /api/chat with {@code think:false} + {@code format:json}, because the
     * OpenAI-compatible endpoint ignores {@code think:false} and so a reasoning
     * model (qwen3) emits slow chain-of-thought prose instead of the function
     * JSON. Every other provider keeps the standard {@link LLMClient#sendPrompt}.
     */
    private static String sendForAction(LLMClient client, String fullSystemPrompt, String userPrompt) {
        String customUrl = AIPlayer.CONFIG.getCustomApiUrl();
        if (OllamaEndpoint.pointsAtOllama(customUrl)) {
            try {
                List<OllamaChatMessage> messages = new java.util.ArrayList<>();
                messages.add(new OllamaChatMessage(OllamaChatMessageRole.SYSTEM, fullSystemPrompt));
                messages.add(new OllamaChatMessage(OllamaChatMessageRole.USER, userPrompt));
                String content = net.shasankp000.OllamaClient.OllamaAPIHelper.chatJson(
                        OllamaEndpoint.baseUrl(),
                        AIPlayer.CONFIG.getOllamaModel(),
                        messages
                ).getContent();
                logger.info("Routed action planning through native Ollama JSON chat");
                return content;
            } catch (Exception e) {
                logger.warn("Native Ollama JSON chat failed ({}); falling back to the OpenAI-compatible client", e.getMessage());
            }
        }
        // Any other backend gets JSON mode too. The caller parses this reply, and a
        // model left to its own devices emits prose instead: qwen3:8b answers
        // "mine 16 oak logs" with ~1200 tokens of narrative. JSON mode also pins
        // temperature to 0 and caps the reply, which bounds that failure.
        return client.sendPromptJson(fullSystemPrompt, userPrompt);
    }

    private static String extractJson(String response) {
        String stripped = stripThinkBlock(response); // Use fix 1 here
        // Try to locate either a JSON object or array
        int objStart = stripped.indexOf("{");
        int objEnd = stripped.lastIndexOf("}") + 1;
        int arrStart = stripped.indexOf("[");
        int arrEnd = stripped.lastIndexOf("]") + 1;
        // Try full JSON object (most likely case)
        if (objStart != -1 && objEnd != -1 && objEnd > objStart) {
            String candidate = stripped.substring(objStart, objEnd);
            if (isValidJson(candidate)) return candidate;
        }
        // Try JSON array (secondary fallback)
        if (arrStart != -1 && arrEnd != -1 && arrEnd > arrStart) {
            String candidate = stripped.substring(arrStart, arrEnd);
            if (isValidJson(candidate)) return candidate;
        }
        logger.error("❌ Could not extract valid JSON from response:\n{}", response);
        return "{}";
    }

    private static boolean isValidJson(String json) {
        try {
            JsonParser.parseString(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripThinkBlock(String response) {
        Matcher matcher = THINK_BLOCK.matcher(response);
        if (matcher.find()) {
            return response.replace(matcher.group(0), "").trim(); // Strip the full <think>...</think>
        }
        return response.trim();
    }

    private static void executeFunction(String userInput, String response) {
        String executionDateTime = getCurrentDateandTime();
        try {
            executor.submit(() -> {
                String cleanedResponse = cleanJsonString(response);
                logger.info("Cleaned JSON Response: {}", cleanedResponse);
                JsonReader reader = new JsonReader(new StringReader(cleanedResponse));
                reader.setStrictness(Strictness.LENIENT);
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                if (jsonObject.has("pipeline")) {
                    AutoFaceEntity.setBotExecutingTask(true);
                    JsonArray pipeline = jsonObject.getAsJsonArray("pipeline");
                    runPipelineLoop(pipeline);
                } else if (jsonObject.has("functionName")) {
                    AutoFaceEntity.setBotExecutingTask(true);
                    String fnName = jsonObject.get("functionName").getAsString();
                    JsonArray paramsArray = jsonObject.get("parameters").getAsJsonArray();
                    Map<String, String> paramMap = new ConcurrentHashMap<>();
                    StringBuilder params = new StringBuilder();
                    for (JsonElement parameter : paramsArray) {
                        JsonObject paramObj = parameter.getAsJsonObject();
                        String paramName = paramObj.get("parameterName").getAsString();
                        String paramValue = paramObj.get("parameterValue").getAsString();
                        paramValue = resolvePlaceholder(paramValue, sharedState);
                        params.append(paramName).append("=").append(paramValue).append(", ");
                        paramMap.put(paramName, paramValue);
                    }
                    logger.info("Executing: {} with {}", fnName, paramMap);
                    callFunction(fnName, paramMap, sharedState).join();
                } else if (jsonObject.has("clarification")) {
                    System.out.println("Detected clarification");
                    String clarification = jsonObject.get("clarification").getAsString();
                    // Save the clarification state
                    ChatContextManager.setPendingClarification(playerUUID, userInput, clarification, botSource.getTextName());
                    // Relay to player in-game
                    sendMessageToPlayer(clarification);
                } else {
                    throw new IllegalStateException("Response must have either functionName or pipeline.");
                }
                // getFunctionResultAndSave(userInput, executionDateTime);
            });
        } catch (JsonSyntaxException | NullPointerException | IllegalStateException e) {
            logger.error("Error processing JSON response: {}", e.getMessage(), e);
        }
    }

    private static void executeFunction(String userInput, String response, LLMClient client) {
        String executionDateTime = getCurrentDateandTime();
        try {
            executor.submit(() -> {
                String cleanedResponse = cleanJsonString(response);
                logger.info("Cleaned JSON Response: {}", cleanedResponse);
                JsonReader reader = new JsonReader(new StringReader(cleanedResponse));
                reader.setStrictness(Strictness.LENIENT);
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                if (jsonObject.has("pipeline")) {
                    AutoFaceEntity.setBotExecutingTask(true);
                    JsonArray pipeline = jsonObject.getAsJsonArray("pipeline");
                    runPipelineLoop(pipeline, client);
                } else if (jsonObject.has("functionName")) {
                    AutoFaceEntity.setBotExecutingTask(true);
                    String fnName = jsonObject.get("functionName").getAsString();
                    JsonArray paramsArray = jsonObject.get("parameters").getAsJsonArray();
                    Map<String, String> paramMap = new ConcurrentHashMap<>();
                    StringBuilder params = new StringBuilder();
                    for (JsonElement parameter : paramsArray) {
                        JsonObject paramObj = parameter.getAsJsonObject();
                        String paramName = paramObj.get("parameterName").getAsString();
                        String paramValue = paramObj.get("parameterValue").getAsString();
                        paramValue = resolvePlaceholder(paramValue, sharedState);
                        params.append(paramName).append("=").append(paramValue).append(", ");
                        paramMap.put(paramName, paramValue);
                    }
                    logger.info("Executing: {} with {}", fnName, paramMap);
                    callFunction(fnName, paramMap, sharedState).join();
                } else if (jsonObject.has("clarification")) {
                    String clarification = jsonObject.get("clarification").getAsString();
                    // Save the clarification state
                    ChatContextManager.setPendingClarification(playerUUID, userInput, clarification, botSource.getTextName());
                    // Relay to player in-game
                    sendMessageToPlayer(clarification);
                } else {
                    throw new IllegalStateException("Response must have either functionName or pipeline.");
                }
                // getFunctionResultAndSave(userInput, executionDateTime);
            });
        } catch (JsonSyntaxException | NullPointerException | IllegalStateException e) {
            logger.error("Error processing JSON response: {}", e.getMessage(), e);
        }
    }

    private static void processLLMOutput(String fullResponse, String botName, CommandSourceStack botSource) {
        logger.info("processLLMOutput called with response: '{}', botName: '{}'", fullResponse, botName);
        if (fullResponse == null || fullResponse.trim().isEmpty()) {
            logger.warn("fullResponse is null or empty");
            return;
        }
        Matcher matcher = THINK_BLOCK.matcher(fullResponse);
        if (matcher.find()) {
            logger.info("Think block found");
            String thinking = matcher.group(1).trim();
            String remainder = fullResponse.replace(matcher.group(0), "").trim();
            ThinkingStateManager.start(botName);
            ChatUtils.sendChatMessages(botSource, botName + " is thinking...");
            for (String line : thinking.split("\\n")) {
                ThinkingStateManager.appendThoughtLine(line);
            }
            ThinkingStateManager.end();
            ChatUtils.sendChatMessages(botSource, botName + " is done thinking!");
            if (!remainder.isEmpty()) {
                logger.info("Sending remainder: '{}'", remainder);
                ChatUtils.sendChatMessages(botSource, botName + ": " + remainder);
            } else {
                logger.info("Remainder is empty");
            }
        } else {
            logger.info("No think block found, sending full response: '{}'", fullResponse);
            ChatUtils.sendChatMessages(botSource, fullResponse);
        }
    }

    private static void sendMessageToPlayer(String message) {
        processLLMOutput(message, botSource.getTextName(), botSource);
    }

    private static void runPipelineLoop(JsonArray pipeline) {
        List<JsonObject> steps = new ArrayList<>();
        List<String> executedSteps = new ArrayList<>();
        for (JsonElement step : pipeline) {
            steps.add(step.getAsJsonObject());
        }

        Deque<JsonObject> pipelineStack = new ArrayDeque<>(steps); // Keep FIFO order
        logger.info("Pipeline stack: {}", pipelineStack);
        OllamaChatRequestBuilder builder = OllamaChatRequestBuilder.getInstance(selectedLM);
        String systemPrompt = FunctionCallerV2.buildPrompt(toolBuilder());
        final int maxRetries = 3;
        int retryCount = 0;

        while (!pipelineStack.isEmpty()) {
            JsonObject step = pipelineStack.pop();
            String functionName = step.get("functionName").getAsString();
            JsonArray parameters = step.getAsJsonArray("parameters");
            Map<String, String> paramMap = new HashMap<>();
            for (JsonElement param : parameters) {
                JsonObject paramObj = param.getAsJsonObject();
                String paramName = paramObj.get("parameterName").getAsString();
                String paramValue = resolvePlaceholder(paramObj.get("parameterValue").getAsString(), sharedState);
                paramMap.put(paramName, paramValue);
            }

            boolean hasUnresolved = PlaceholderResolver.hasUnresolved(paramMap);
            if (hasUnresolved) {
                logger.warn("⚠️ One or more parameters in step '{}' are unresolved. Triggering LLM fallback.", functionName);
                if (retryCount >= maxRetries) {
                    logger.error("❌ Max LLM fallback retries reached due to unresolved parameters. Aborting.");
                    break;
                }
                String newPrompt = "The following steps in the pipeline were successfully executed:\n"
                        + String.join("\n", executedSteps)
                        + "\n\nExecution failed at step: " + functionName
                        + "\nCause: One or more placeholders could not be resolved from shared state.";
                try {
                    State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
                    InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1);
                    map.updateMap();
                    // If method returns Map<String, String>
                    Map<String, String> surroundingsStr = map.summarizeSurroundings();
                    Map<String, Object> surroundings = new HashMap<>();
                    surroundings.putAll(surroundingsStr);

                    String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
                    String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;

                    List<OllamaChatMessage> messages = new java.util.ArrayList<>();
                    messages.add(new OllamaChatMessage(OllamaChatMessageRole.SYSTEM, fullSystemPrompt));
                    messages.add(new OllamaChatMessage(OllamaChatMessageRole.USER, newPrompt));

                    net.shasankp000.OllamaClient.OllamaThinkingResponse thinkingResponse =
                            net.shasankp000.OllamaClient.OllamaAPIHelper.smartChat(
                                    ollamaAPI,
                                    OllamaEndpoint.baseUrl(),
                                    net.shasankp000.AIPlayer.CONFIG.getOllamaModel(),
                                    messages
                            );

                    String llmResponse = thinkingResponse.getContent();
                    logger.info("Raw LLM response: {}", llmResponse);
                    String cleanedResponse = stripThinkBlock(llmResponse);
                    String jsonPart = extractJson(cleanedResponse);
                    logger.info("Extracted JSON: {}", jsonPart);
                    JsonObject llmResponseObj = JsonParser.parseString(jsonPart).getAsJsonObject();

                    if (llmResponseObj.has("pipeline")) {
                        logger.info("LLM provided NEW pipeline. Rebuilding stack.");
                        retryCount = 0;
                        JsonArray newPipeline = llmResponseObj.getAsJsonArray("pipeline");
                        pipelineStack.clear();
                        List<JsonObject> newSteps = new ArrayList<>();
                        for (JsonElement e : newPipeline) {
                            newSteps.add(e.getAsJsonObject());
                        }
                        pipelineStack.addAll(newSteps);
                        continue;
                    } else if (llmResponseObj.has("clarification")) {
                        logger.info("LLM requested clarification. Relaying to player.");
                        String clarification = llmResponseObj.get("clarification").getAsString();
                        ChatContextManager.setPendingClarification(playerUUID, "A recent action failed.", clarification, botSource.getTextName());
                        sendMessageToPlayer(clarification);
                        break;
                    } else {
                        logger.warn("LLM did not return a pipeline or clarification. Exiting.");
                        break;
                    }
                } catch (Exception e) {
                    logger.error("❌ Error in LLM fallback after unresolved parameters: {}", e.getMessage(), e);
                    retryCount++;
                    continue;
                }
            }

            logger.info("Running function: " + functionName + " with " + paramMap);
            callFunction(functionName, paramMap, sharedState).join(); // Sync call
            logger.info("Function output: {}", lastFunctionOutput);
            parseOutputValues(functionName, lastFunctionOutput);

            // Short wait for state to settle (e.g., for movement tools)
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                logger.warn("Interrupted during state settle wait");
            }

            // Get bot entity
            ServerPlayer bot = botSource.getPlayer();
            if (bot == null) {
                logger.error("Bot entity not found for verification");
                continue;
            }

            // Use state-based verifier
            ToolVerifiers.StateVerifier verifier = ToolVerifiers.VERIFIER_REGISTRY.get(functionName);
            ToolVerifiers.VerificationResult result = (verifier == null)
                    ? new ToolVerifiers.VerificationResult(true, null)
                    : verifier.verify(paramMap, sharedState, bot);

            if (result.success) {
                logger.info("✅ Verifier passed for {} with data: {}", functionName, result.data);
                executedSteps.add(functionName + ", Output: " + functionOutput);
            } else {
                logger.warn("❌ Verifier failed for {} with data: {}", functionName, result.data);
                if (retryCount >= maxRetries) {
                    logger.error("❌ Max LLM fallback retries reached due to verifier failures. Aborting.");
                    break;
                }
                String newPrompt = "The following steps were executed successfully:\n"
                        + String.join("\n", executedSteps)
                        + "\n\nExecution failed at step: " + functionName
                        + "\nFunction output: " + functionOutput
                        + "\nVerification details: " + result.data;
                try {
                    State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
                    InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1);
                    map.updateMap();
                    // If method returns Map<String, String>
                    Map<String, String> surroundingsStr = map.summarizeSurroundings();
                    Map<String, Object> surroundings = new HashMap<>();
                    surroundings.putAll(surroundingsStr);

                    String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
                    String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;
                    OllamaChatRequestModel requestModel = builder
                            .withMessage(OllamaChatMessageRole.SYSTEM, fullSystemPrompt)
                            .withMessage(OllamaChatMessageRole.USER, newPrompt)
                            .build();
                    OllamaChatResult llmResult = ollamaAPI.chat(requestModel);
                    String llmResponse = llmResult.getResponse();
                    logger.info("Raw LLM response: {}", llmResponse);
                    String cleanedResponse = stripThinkBlock(llmResponse);
                    String jsonPart = extractJson(cleanedResponse);
                    logger.info("Extracted JSON: {}", jsonPart);
                    JsonObject llmResponseObj = JsonParser.parseString(jsonPart).getAsJsonObject();

                    if (llmResponseObj.has("pipeline")) {
                        logger.info("LLM provided NEW pipeline. Rebuilding stack.");
                        retryCount = 0;
                        JsonArray newPipeline = llmResponseObj.getAsJsonArray("pipeline");
                        pipelineStack.clear();
                        List<JsonObject> newSteps = new ArrayList<>();
                        for (JsonElement e : newPipeline) {
                            newSteps.add(e.getAsJsonObject());
                        }
                        pipelineStack.addAll(newSteps);
                    } else if (llmResponseObj.has("clarification")) {
                        logger.info("LLM requested clarification. Relaying to player.");
                        String clarification = llmResponseObj.get("clarification").getAsString();
                        ChatContextManager.setPendingClarification(playerUUID, "A recent action failed.", clarification, botSource.getTextName());
                        sendMessageToPlayer(clarification);
                        break;
                    } else {
                        logger.warn("LLM did not return a pipeline or clarification. Exiting.");
                        break;
                    }
                } catch (Exception e) {
                    logger.error("❌ Error in LLM fallback after verifier failure: {}", e.getMessage(), e);
                    retryCount++;
                }
            }
        }
        blockDetectionUnit.setIsBlockDetectionActive(false);
        if (botSource != null && botSource.getPlayer() != null) {
            NavigationService.cancel(botSource.getServer(), botSource.getPlayer().getUUID(), "Function pipeline reset");
        }
        AutoFaceEntity.setBotExecutingTask(false);
        AutoFaceEntity.isBotMoving = false;
        logger.info("✔️ Autoface module has been reset.");
    }

    // overloaded method to handle the other LLM providers
    private static void runPipelineLoop(JsonArray pipeline, LLMClient client) {
        List<JsonObject> steps = new ArrayList<>();
        List<String> executedSteps = new ArrayList<>();
        for (JsonElement step : pipeline) {
            steps.add(step.getAsJsonObject());
        }

        Deque<JsonObject> pipelineStack = new ArrayDeque<>(steps);
        logger.info("Pipeline stack: {}", pipelineStack);
        String systemPrompt = FunctionCallerV2.buildPrompt(toolBuilder());
        final int maxRetries = 3;
        int retryCount = 0;

        while (!pipelineStack.isEmpty()) {
            JsonObject step = pipelineStack.pop();
            String functionName = step.get("functionName").getAsString();
            JsonArray parameters = step.getAsJsonArray("parameters");
            Map<String, String> paramMap = new HashMap<>();
            for (JsonElement param : parameters) {
                JsonObject paramObj = param.getAsJsonObject();
                String paramName = paramObj.get("parameterName").getAsString();
                String paramValue = resolvePlaceholder(paramObj.get("parameterValue").getAsString(), sharedState);
                paramMap.put(paramName, paramValue);
            }

            boolean hasUnresolved = PlaceholderResolver.hasUnresolved(paramMap);
            if (hasUnresolved) {
                logger.warn("⚠️ One or more parameters in step '{}' are unresolved. Triggering LLM fallback.", functionName);
                if (retryCount >= maxRetries) {
                    logger.error("❌ Max LLM fallback retries reached due to unresolved parameters. Aborting.");
                    break;
                }
                String newPrompt = "The following steps in the pipeline were successfully executed:\n"
                        + String.join("\n", executedSteps)
                        + "\n\nExecution failed at step: " + functionName
                        + "\nCause: One or more placeholders could not be resolved from shared state.";
                try {
                    State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
                    InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1);
                    map.updateMap();
                    // If method returns Map<String, String>
                    Map<String, String> surroundingsStr = map.summarizeSurroundings();
                    Map<String, Object> surroundings = new HashMap<>();
                    surroundings.putAll(surroundingsStr);

                    String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
                    String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;

                    String llmResponse = client.sendPrompt(fullSystemPrompt, newPrompt);
                    logger.info("Raw LLM response: {}", llmResponse);
                    String cleanedResponse = stripThinkBlock(llmResponse);
                    String jsonPart = extractJson(cleanedResponse);
                    logger.info("Extracted JSON: {}", jsonPart);
                    JsonObject llmResponseObj = JsonParser.parseString(jsonPart).getAsJsonObject();

                    if (llmResponseObj.has("pipeline")) {
                        logger.info("LLM provided NEW pipeline. Rebuilding stack.");
                        retryCount = 0;
                        JsonArray newPipeline = llmResponseObj.getAsJsonArray("pipeline");
                        pipelineStack.clear();
                        List<JsonObject> newSteps = new ArrayList<>();
                        for (JsonElement e : newPipeline) {
                            newSteps.add(e.getAsJsonObject());
                        }
                        pipelineStack.addAll(newSteps);
                        continue;
                    } else if (llmResponseObj.has("clarification")) {
                        logger.info("LLM requested clarification. Relaying to player.");
                        String clarification = llmResponseObj.get("clarification").getAsString();
                        ChatContextManager.setPendingClarification(playerUUID, "A recent action failed.", clarification, botSource.getTextName());
                        sendMessageToPlayer(clarification);
                        break;
                    } else {
                        logger.warn("LLM did not return a pipeline or clarification. Exiting.");
                        break;
                    }
                } catch (Exception e) {
                    logger.error("❌ Error in LLM fallback after unresolved parameters: {}", e.getMessage(), e);
                    retryCount++;
                    continue;
                }
            }

            logger.info("Running function: {} with {}", functionName, paramMap);
            callFunction(functionName, paramMap, sharedState).join();
            logger.info("Function output: {}", lastFunctionOutput);
            parseOutputValues(functionName, lastFunctionOutput);

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                logger.warn("Interrupted during state settle wait");
            }

            ServerPlayer bot = botSource.getPlayer();
            if (bot == null) {
                logger.error("Bot entity not found for verification");
                continue;
            }

            ToolVerifiers.StateVerifier verifier = ToolVerifiers.VERIFIER_REGISTRY.get(functionName);
            ToolVerifiers.VerificationResult result = (verifier == null)
                    ? new ToolVerifiers.VerificationResult(true, null)
                    : verifier.verify(paramMap, sharedState, bot);

            if (result.success) {
                logger.info("✅ Verifier passed for {} with data: {}", functionName, result.data);
                executedSteps.add(functionName + ", Output: " + functionOutput);
            } else {
                logger.warn("❌ Verifier failed for {} with data: {}", functionName, result.data);
                if (retryCount >= maxRetries) {
                    logger.error("❌ Max LLM fallback retries reached due to verifier failures. Aborting.");
                    break;
                }
                String newPrompt = "The following steps were executed successfully:\n"
                        + String.join("\n", executedSteps)
                        + "\n\nExecution failed at step: " + functionName
                        + "\nFunction output: " + functionOutput
                        + "\nVerification details: " + result.data;
                try {
                    State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
                    InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1);
                    map.updateMap();
                    Map<String, String> surroundingsStr = map.summarizeSurroundings();
                    Map<String, Object> surroundings = new HashMap<>();
                    surroundings.putAll(surroundingsStr);

                    String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
                    String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;

                    String llmResponse = client.sendPrompt(fullSystemPrompt, newPrompt);
                    logger.info("Raw LLM response: {}", llmResponse);
                    String cleanedResponse = stripThinkBlock(llmResponse);
                    String jsonPart = extractJson(cleanedResponse);
                    logger.info("Extracted JSON: {}", jsonPart);
                    JsonObject llmResponseObj = JsonParser.parseString(jsonPart).getAsJsonObject();

                    if (llmResponseObj.has("pipeline")) {
                        logger.info("LLM provided NEW pipeline. Rebuilding stack.");
                        retryCount = 0;
                        JsonArray newPipeline = llmResponseObj.getAsJsonArray("pipeline");
                        pipelineStack.clear();
                        List<JsonObject> newSteps = new ArrayList<>();
                        for (JsonElement e : newPipeline) {
                            newSteps.add(e.getAsJsonObject());
                        }
                        pipelineStack.addAll(newSteps);
                    } else if (llmResponseObj.has("clarification")) {
                        logger.info("LLM requested clarification. Relaying to player.");
                        String clarification = llmResponseObj.get("clarification").getAsString();
                        ChatContextManager.setPendingClarification(playerUUID, "A recent action failed.", clarification, botSource.getTextName());
                        sendMessageToPlayer(clarification);
                        break;
                    } else {
                        logger.warn("LLM did not return a pipeline or clarification. Exiting.");
                        break;
                    }
                } catch (Exception e) {
                    logger.error("❌ Error in LLM fallback after verifier failure: {}", e.getMessage(), e);
                    retryCount++;
                }
            }
        }
        blockDetectionUnit.setIsBlockDetectionActive(false);
        if (botSource != null && botSource.getPlayer() != null) {
            NavigationService.cancel(botSource.getServer(), botSource.getPlayer().getUUID(), "Function pipeline reset");
        }
        AutoFaceEntity.setBotExecutingTask(false);
        AutoFaceEntity.isBotMoving = false;
        logger.info("✔️ Autoface module has been reset.");
    }



    private static void parseOutputValues(String functionName, String output) {
        // The PARSING view, not the prompt's advertised one. They used to be the
        // same table, so dropping an entry from the prompt for tidiness also made
        // the runtime unable to read that tool's output. See ToolStateKeys.
        List<String> keys = ToolStateKeys.parseable().get(functionName);
        if (keys == null || keys.isEmpty()) return;

        List<Object> values = new ArrayList<>();
        switch (functionName) {
            case "goTo" -> {
                Matcher matcher = Pattern.compile("x[:=]\\s*(-?\\d+)\\s*y[:=]\\s*(-?\\d+)\\s*z[:=]\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(Integer.parseInt(matcher.group(1)));
                    values.add(Integer.parseInt(matcher.group(2)));
                    values.add(Integer.parseInt(matcher.group(3)));
                }
            }
            case "detectBlocks" -> {
                Matcher matcher = Pattern.compile(".*found at (-?\\d+) (-?\\d+) (-?\\d+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(Integer.parseInt(matcher.group(1)));
                    values.add(Integer.parseInt(matcher.group(2)));
                    values.add(Integer.parseInt(matcher.group(3)));
                }
            }
            case "turn" -> {
                Matcher matcher = Pattern.compile("Now facing (\\w+) which is in (\\w+).*in (\\w+) axis", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(matcher.group(1));
                    values.add(matcher.group(2));
                    values.add(matcher.group(3));
                }
            }
            case "look" -> {
                Matcher matcher = Pattern.compile("Now facing cardinal direction (\\w+) which is in (\\w+).*in (\\w+) axis", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(matcher.group(1));
                    values.add(matcher.group(2));
                    values.add(matcher.group(3));
                }
            }
            case "mineBlock" -> {
                if (output.contains("Mining complete!")) {
                    values.add("success");
                } else if (output.contains("⚠️ Failed to mine block")) {
                    values.add("failed");
                }
            }
            case "getOxygenLevel" -> {
                Matcher matcher = Pattern.compile("Oxygen Level[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) values.add(Integer.parseInt(matcher.group(1)));
            }
            case "getHungerLevel" -> {
                Matcher matcher = Pattern.compile("Hunger Level[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) values.add(Integer.parseInt(matcher.group(1)));
            }
            case "getHealthLevel" -> {
                Matcher matcher = Pattern.compile("Remaining hearts[:=]\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) values.add(Double.parseDouble(matcher.group(1)));
            }
            case "faceBlock" -> {
                Matcher matcher = Pattern.compile("Yaw[:=]\\s*([\\d.-]+).*Pitch[:=]\\s*([\\d.-]+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(Double.parseDouble(matcher.group(1)));
                    values.add(Double.parseDouble(matcher.group(2)));
                }
            }
            case "faceEntity" -> {
                Matcher matcher = Pattern.compile("Facing entity[:=]\\s*(.+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) values.add(matcher.group(1));
            }
            case "chartPathToBlock" -> {
                Matcher matcher = Pattern.compile("Bot is at (-?\\d+) (-?\\d+) (-?\\d+)").matcher(output);
                if (matcher.find()) {
                    values.add(Integer.parseInt(matcher.group(1)));
                    values.add(Integer.parseInt(matcher.group(2)));
                    values.add(Integer.parseInt(matcher.group(3)));
                }
            }
        }

        if (values.size() == keys.size()) {
            updateState(keys, values, sharedState);
        } else {
            logger.warn("❌ Mismatch in keys/values for {} → keys: {}, values: {}", functionName, keys, values);
        }
    }

    private static void getFunctionResultAndSave(String userInput, String executionDateTime) {
        try {
            // Generate context synchronously
            String eventContext = generatePromptContext(userInput);
            // Get embedding client
            net.shasankp000.ServiceLLMClients.EmbeddingClient embeddingClient =
                    net.shasankp000.FilingSystem.EmbeddingClientFactory.createClient();
            // Generate event embedding synchronously
            List<Double> eventEmbedding;
            List<Double> eventContextEmbedding;
            try {
                eventEmbedding = embeddingClient.generateEmbedding(userInput);
                // Generate event context embedding synchronously
                eventContextEmbedding = embeddingClient.generateEmbedding(eventContext);
            } catch (Exception e) {
                logger.error("Failed to generate embeddings", e);
                throw new RuntimeException(e);
            }
            // Wait until functionOutput is a valid string
            while (functionOutput == null || !(functionOutput instanceof String)) {
                try {
                    Thread.sleep(500L); // Check every 500ms
                } catch (InterruptedException e) {
                    logger.error("Couldn't get function call output");
                    throw new RuntimeException(e);
                }
            }
            System.out.println("Received output: " + functionOutput);
            // Generate result embedding based on the function output
            List<Double> resultEmbedding;
            try {
                resultEmbedding = embeddingClient.generateEmbedding(functionOutput);
            } catch (Exception e) {
                logger.error("Failed to generate result embedding", e);
                throw new RuntimeException(e);
            }
            // Create execution record and save it
            ExecutionRecord executionRecord = new ExecutionRecord(executionDateTime, userInput, eventContext, functionOutput, eventEmbedding, eventContextEmbedding, resultEmbedding);
            executionRecord.updateRecords();
            // Clear the functionOutput to reset state
            functionOutput = null;
            System.out.println("Event data saved successfully.");
        } catch (Exception e) {
            // Log or handle the exception
            logger.error("Error occurred while processing the function result: ", e);
            throw new RuntimeException(e);
        }
    }

    private static String resolvePlaceholder(String value, Map<String, Object> state) {
        String resolved = PlaceholderResolver.resolve(value, key -> SharedStateUtils.getValue(state, key));
        if (PlaceholderResolver.isUnresolved(resolved)) {
            // Not "0". A guessed coordinate used to send the bot to the world origin;
            // the sentinel lets the callers refuse the step instead. See
            // PlaceholderResolver for why an omitted parameter is NOT this case.
            logger.warn("⚠️ Placeholder '{}' has no value in bot state — marking the step unresolved", value);
        }
        return resolved;
    }

    /**
     * Resolve a parameter that must be a coordinate, failing loudly when it
     * cannot be resolved.
     *
     * <p>Guessing a coordinate is worse than aborting: when the LLM referenced a
     * state key the runtime never publishes, the step used to be executed as a
     * walk to the world origin. Applies to the resolved value, since callers pass
     * a map that {@link #resolvePlaceholder} has already been through.
     *
     * @throws IllegalStateException when the value is absent or unresolved
     */
    private static String resolveCoord(String value, Map<String, Object> state, String axis) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("No " + axis + " value supplied for this step");
        }
        if (PlaceholderResolver.isUnresolved(value)) {
            throw new IllegalStateException("Unresolved coordinate '" + axis
                    + "' (guessing 0 would move the bot to the world origin)");
        }
        if (value.startsWith("$")) {
            String key = value.substring(1);
            Object resolvedObj = SharedStateUtils.getValue(state, key);
            if (resolvedObj == null) {
                throw new IllegalStateException("Unresolved coordinate placeholder '" + key
                        + "' for " + axis + " (defaulting to 0 would move the bot to the world origin)");
            }
            logger.debug("🔁 Resolved coordinate {} from {} → {}", axis, key, resolvedObj);
            return resolvedObj.toString();
        }
        return value;
    }

    private static void updateState(List<String> keys, List<Object> values, Map<String, Object> state) {
        for (int i = 0; i < keys.size(); i++) {
            SharedStateUtils.setValue(state, keys.get(i), values.get(i));
            logger.info("📌 Updated sharedState: {} → {}", keys.get(i), values.get(i));
        }
    }

    private static int parseIntSafe(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static CompletableFuture<Void> callFunction(String functionName, Map<String, String> paramMap, Map<String, Object> state) {
        // Last line of defence, and the only one the single-function paths have:
        // three of the four callers of this method resolve placeholders and call
        // straight through with no check, so an unresolvable $placeholder reached
        // the tools as "0". Refuse rather than guess a position.
        List<String> unresolved = PlaceholderResolver.unresolvedNames(paramMap);
        if (!unresolved.isEmpty()) {
            String detail = String.join(", ", unresolved);
            logger.error("⛔ Refusing to run '{}': unresolved parameter(s) [{}] — the referenced bot state has no value",
                    functionName, detail);
            getFunctionOutput("⚠️ Failed to run " + functionName
                    + ": could not resolve the bot state needed for [" + detail + "]");
            return CompletableFuture.completedFuture(null);
        }

        if ("mineBlock".equals(functionName)) {
            try {
                int targetX = Integer.parseInt(resolveCoord(paramMap.get("targetX"), state, "targetX"));
                int targetY = Integer.parseInt(resolveCoord(paramMap.get("targetY"), state, "targetY"));
                int targetZ = Integer.parseInt(resolveCoord(paramMap.get("targetZ"), state, "targetZ"));
                logger.info("Calling method: mineBlock with targetX={} targetY={} targetZ={}",
                        targetX, targetY, targetZ);
                if (botSource == null || botSource.getPlayer() == null) {
                    getFunctionOutput("⚠️ Failed to mine block: Bot not found");
                    return CompletableFuture.completedFuture(null);
                }
                return MiningTool.mineBlock(botSource.getPlayer(), new BlockPos(targetX, targetY, targetZ))
                        .handle((result, error) -> {
                            if (error != null) {
                                logger.error("Mining failed", error);
                                getFunctionOutput("⚠️ Failed to mine block: " + error.getMessage());
                            } else {
                                getFunctionOutput(result.functionMessage());
                            }
                            reportCompletion(functionName, paramMap);
                            return null;
                        });
            } catch (Exception e) {
                logger.error("Could not start mining", e);
                getFunctionOutput("⚠️ Failed to mine block: " + e.getMessage());
                return CompletableFuture.completedFuture(null);
            }
        }

        return CompletableFuture.runAsync(() -> {
            logger.info("🔧 callFunction: {} with params: {}", functionName, paramMap);

            switch (functionName) {
                case "goTo" -> {
                    int x = Integer.parseInt(resolveCoord(paramMap.get("x"), state, "x"));
                    int y = Integer.parseInt(resolveCoord(paramMap.get("y"), state, "y"));
                    int z = Integer.parseInt(resolveCoord(paramMap.get("z"), state, "z"));
                    boolean sprint = Boolean.parseBoolean(resolvePlaceholder(paramMap.get("sprint"), state));
                    logger.info("Calling method: goTo with x={} y={} z={} sprint={}", x, y, z, sprint);
                    Tools.goTo(x, y, z, sprint);
                }
                case "chartPathToBlock" -> {
                    int targetX = Integer.parseInt(resolveCoord(paramMap.get("targetX"), state, "targetX"));
                    int targetY = Integer.parseInt(resolveCoord(paramMap.get("targetY"), state, "targetY"));
                    int targetZ = Integer.parseInt(resolveCoord(paramMap.get("targetZ"), state, "targetZ"));
                    String blockType = resolvePlaceholder(paramMap.get("blockType"), state);
                    logger.info("Calling method: chartPathToBlock with targetX={} targetY={} targetZ={} blockType={}", targetX, targetY, targetZ, blockType);
                    Tools.chartPathToBlock(targetX, targetY, targetZ, blockType);
                }
                case "faceBlock" -> {
                    int targetX = Integer.parseInt(resolveCoord(paramMap.get("targetX"), state, "targetX"));
                    int targetY = Integer.parseInt(resolveCoord(paramMap.get("targetY"), state, "targetY"));
                    int targetZ = Integer.parseInt(resolveCoord(paramMap.get("targetZ"), state, "targetZ"));
                    logger.info("Calling method: faceBlock with targetX={} targetY={} targetZ={}", targetX, targetY, targetZ);
                    Tools.faceBlock(targetX, targetY, targetZ);
                }
                case "faceEntity" -> {
                    int targetX = Integer.parseInt(resolveCoord(paramMap.get("targetX"), state, "targetX"));
                    int targetY = Integer.parseInt(resolveCoord(paramMap.get("targetY"), state, "targetY"));
                    int targetZ = Integer.parseInt(resolveCoord(paramMap.get("targetZ"), state, "targetZ"));
                    logger.info("Calling method: faceEntity with targetX={} targetY={} targetZ={}", targetX, targetY, targetZ);
                    Tools.faceEntity(targetX, targetY, targetZ);
                }
                case "detectBlocks" -> {
                    String blockType = resolvePlaceholder(paramMap.get("blockType"), state);
                    logger.info("Calling method: detectBlocks with blockType={}", blockType);
                    Tools.detectBlocks(blockType);
                }
                case "turn" -> {
                    String direction = resolvePlaceholder(paramMap.get("direction"), state);
                    logger.info("Calling method: turn with direction={}", direction);
                    Tools.turn(direction);
                }
                case "look" -> {
                    String cardinalDirection = resolvePlaceholder(paramMap.get("cardinalDirection"), state);
                    logger.info("Calling method: look with cardinal direction={}", cardinalDirection);
                    Tools.look(cardinalDirection);
                }
                case "placeBlock" -> {
                    int targetX = Integer.parseInt(resolveCoord(paramMap.get("targetX"), state, "targetX"));
                    int targetY = Integer.parseInt(resolveCoord(paramMap.get("targetY"), state, "targetY"));
                    int targetZ = Integer.parseInt(resolveCoord(paramMap.get("targetZ"), state, "targetZ"));
                    String blockType = resolvePlaceholder(paramMap.get("blockType"), state);
                    logger.info("Calling method: placeBlock with targetX={} targetY={} targetZ={} blockType={}",
                            targetX, targetY, targetZ, blockType);
                    Tools.placeBlock(targetX, targetY, targetZ, blockType);
                }
                case "getOxygenLevel" -> {
                    logger.info("Calling method: getOxygenLevel");
                    Tools.getOxygenLevel();
                }
                case "getHungerLevel" -> {
                    logger.info("Calling method: getHungerLevel");
                    Tools.getHungerLevel();
                }
                case "getHealthLevel" -> {
                    logger.info("Calling method: getHealthLevel");
                    Tools.getHealthLevel();
                }
                case "equipArmor" -> {
                    logger.info("Calling method: equipArmor");
                    Tools.equipArmor();
                }
                case "updateState" -> {
                    String keysRaw = paramMap.get("keys");
                    String valuesRaw = paramMap.get("values");
                    List<String> keys = List.of(keysRaw.split(","));
                    List<String> valueStrings = List.of(valuesRaw.split(","));
                    List<Object> values = new ArrayList<>();
                    for (String v : valueStrings) {
                        try {
                            values.add(Integer.parseInt(v));
                        } catch (NumberFormatException e1) {
                            try {
                                values.add(Double.parseDouble(v));
                            } catch (NumberFormatException e2) {
                                values.add(v);  // Fallback to string
                            }
                        }
                    }
                    updateState(keys, values, state);
                    logger.info("Called updateState with keys={} and values={}", keys, values);
                }
                // Add this new case to your existing switch statement inside the callFunction method.
                case "webSearch" -> {
                    String query = resolvePlaceholder(paramMap.get("query"), state);
                    logger.info("Calling method: webSearch with query='{}'", query);
                    Tools.webSearch(query);
                }
                case "searchBlocks" -> {
                    String blockType = resolvePlaceholder(paramMap.get("blockType"), state);
                    // Defaults, not 0. The LLM routinely omits these, and zeroes make
                    // SearchBlocks' expanding-radius loop spin forever (increment 0
                    // never reaches maxRadius 0). Values match the examples the tool
                    // advertises in ToolRegistry.
                    int initialRadius = parseIntSafe(resolvePlaceholder(paramMap.get("initialRadius"), state), 10);
                    int maxRadius = parseIntSafe(resolvePlaceholder(paramMap.get("maxRadius"), state), 100);
                    int radiusIncrement = parseIntSafe(resolvePlaceholder(paramMap.get("radiusIncrement"), state), 20);
                    logger.info("Calling method: searchBlocks with blockType={} initialRadius={} maxRadius={} increment={}",
                            blockType, initialRadius, maxRadius, radiusIncrement);

                    // Call searchBlocks and capture the result
                    if (botSource != null && botSource.getPlayer() != null) {
                        ServerPlayer bot = botSource.getPlayer();
                        BlockPos result = net.shasankp000.Tools.SearchBlocks.searchBlock(
                            bot,
                            blockType,
                            initialRadius,
                            maxRadius,
                            radiusIncrement
                        );

                        // Store result in SharedState for next steps
                        if (result != null) {
                            SharedStateUtils.setValue(state, "found_block_x", result.getX());
                            SharedStateUtils.setValue(state, "found_block_y", result.getY());
                            SharedStateUtils.setValue(state, "found_block_z", result.getZ());
                            SharedStateUtils.setValue(state, "found_block_type", blockType);
                            SharedStateUtils.setValue(state, "search_success", true);

                            // `found_block_*` is what the deterministic planner reads
                            // (convertStepToParams). The LLM pipeline instead follows
                            // the placeholder table and references
                            // `$lastDetectedBlock.x/y/z`, which only `detectBlocks`
                            // used to publish — so a successful searchBlocks left
                            // those unresolved and the following goTo was refused.
                            // Publish both names over the same value.
                            SharedStateUtils.setValue(state, "lastDetectedBlock.x", result.getX());
                            SharedStateUtils.setValue(state, "lastDetectedBlock.y", result.getY());
                            SharedStateUtils.setValue(state, "lastDetectedBlock.z", result.getZ());

                            logger.info("✓ searchBlocks found {} at ({}, {}, {})", blockType, result.getX(), result.getY(), result.getZ());
                        } else {
                            SharedStateUtils.setValue(state, "search_success", false);
                            logger.warn("searchBlocks did not find {} within radius {}", blockType, maxRadius);
                        }
                    } else {
                        logger.error("Cannot execute searchBlocks: bot is null");
                    }
                }
                case "craft" -> {
                    String item = resolvePlaceholder(paramMap.get("item"), state);
                    int count = parseIntSafe(resolvePlaceholder(paramMap.get("count"), state), 1);
                    logger.info("Calling method: craft with item={} count={}", item, count);
                    ServerPlayer bot = (botSource != null) ? botSource.getPlayer() : null;
                    if (bot == null) {
                        getFunctionOutput("Bot not found.");
                    } else {
                        getFunctionOutput(CraftingTool.craft(bot, item, count).join());
                    }
                }
                case "farmTill" -> {
                    int x = Integer.parseInt(resolveCoord(paramMap.get("x"), state, "x"));
                    int y = Integer.parseInt(resolveCoord(paramMap.get("y"), state, "y"));
                    int z = Integer.parseInt(resolveCoord(paramMap.get("z"), state, "z"));
                    ServerPlayer bot = (botSource != null) ? botSource.getPlayer() : null;
                    logger.info("Calling method: farmTill at ({}, {}, {})", x, y, z);
                    getFunctionOutput(bot == null ? "Bot not found."
                            : FarmingTool.till(bot, new BlockPos(x, y, z)).join());
                }
                case "farmPlant" -> {
                    int x = Integer.parseInt(resolveCoord(paramMap.get("x"), state, "x"));
                    int y = Integer.parseInt(resolveCoord(paramMap.get("y"), state, "y"));
                    int z = Integer.parseInt(resolveCoord(paramMap.get("z"), state, "z"));
                    String seed = resolvePlaceholder(paramMap.get("seed"), state);
                    ServerPlayer bot = (botSource != null) ? botSource.getPlayer() : null;
                    logger.info("Calling method: farmPlant {} at ({}, {}, {})", seed, x, y, z);
                    getFunctionOutput(bot == null ? "Bot not found."
                            : FarmingTool.plant(bot, new BlockPos(x, y, z), seed).join());
                }
                case "farmHarvest" -> {
                    int x = Integer.parseInt(resolveCoord(paramMap.get("x"), state, "x"));
                    int y = Integer.parseInt(resolveCoord(paramMap.get("y"), state, "y"));
                    int z = Integer.parseInt(resolveCoord(paramMap.get("z"), state, "z"));
                    ServerPlayer bot = (botSource != null) ? botSource.getPlayer() : null;
                    logger.info("Calling method: farmHarvest at ({}, {}, {})", x, y, z);
                    getFunctionOutput(bot == null ? "Bot not found."
                            : FarmingTool.harvest(bot, new BlockPos(x, y, z)).join());
                }
                case "farm" -> {
                    String seed = resolvePlaceholder(paramMap.get("seed"), state);
                    ServerPlayer bot = (botSource != null) ? botSource.getPlayer() : null;
                    logger.info("Calling method: farm with seed={}", seed);
                    getFunctionOutput(bot == null ? "Bot not found."
                            : FarmingTool.farm(bot, seed).join());
                }
                case "build" -> {
                    String structure = resolvePlaceholder(paramMap.get("structure"), state);
                    String blockType = resolvePlaceholder(paramMap.get("blockType"), state);
                    ServerPlayer bot = (botSource != null) ? botSource.getPlayer() : null;
                    logger.info("Calling method: build structure={} blockType={} (with recovery)", structure, blockType);
                    getFunctionOutput(bot == null ? "Bot not found."
                            : StructureBuilder.buildWithRecovery(bot, structure, blockType).join());
                }
                case "searchFlatSite" -> {
                    String structure = resolvePlaceholder(paramMap.get("structure"), state);
                    ServerPlayer bot = (botSource != null) ? botSource.getPlayer() : null;
                    logger.info("Calling method: searchFlatSite for structure={}", structure);
                    if (bot == null) {
                        getFunctionOutput("Bot not found.");
                    } else {
                        int[] dims = StructureBuilder.dimensions(structure);
                        if (dims == null) {
                            SharedStateUtils.setValue(state, "search_site_success", false);
                            getFunctionOutput("❌ Unknown structure: " + structure);
                        } else {
                            BlockPos site = SiteSurveyor.findFlatSite(bot, dims[0], dims[1], dims[2], 12);
                            if (site != null) {
                                // Publish the site under both key spellings. The LLM
                                // regularly refers to it as "lastFlatSite" when it
                                // writes a follow-up goTo step, so registering that
                                // alias keeps its plans working instead of leaving
                                // $lastFlatSite.* unresolved (which used to default
                                // to 0,0,0 and send the bot to the world origin).
                                SharedStateUtils.setValue(state, "found_site_x", site.getX());
                                SharedStateUtils.setValue(state, "found_site_y", site.getY());
                                SharedStateUtils.setValue(state, "found_site_z", site.getZ());
                                SharedStateUtils.setValue(state, "foundSite.x", site.getX());
                                SharedStateUtils.setValue(state, "foundSite.y", site.getY());
                                SharedStateUtils.setValue(state, "foundSite.z", site.getZ());
                                SharedStateUtils.setValue(state, "lastFlatSite.x", site.getX());
                                SharedStateUtils.setValue(state, "lastFlatSite.y", site.getY());
                                SharedStateUtils.setValue(state, "lastFlatSite.z", site.getZ());
                                SharedStateUtils.setValue(state, "search_site_success", true);
                                logger.info("✓ searchFlatSite found site at ({}, {}, {})",
                                        site.getX(), site.getY(), site.getZ());
                                getFunctionOutput("✅ Found flat site at (" + site.getX() + ", "
                                        + site.getY() + ", " + site.getZ() + ")");
                            } else {
                                SharedStateUtils.setValue(state, "search_site_success", false);
                                getFunctionOutput("❌ No flat site found nearby");
                            }
                        }
                    }
                }
                case "terraform" -> {
                    String structure = resolvePlaceholder(paramMap.get("structure"), state);
                    ServerPlayer bot = (botSource != null) ? botSource.getPlayer() : null;
                    logger.info("Calling method: terraform for structure={}", structure);
                    getFunctionOutput(bot == null ? "Bot not found."
                            : StructureBuilder.terraform(bot, structure).join());
                }
                case "collect" -> {
                    ServerPlayer bot = (botSource != null) ? botSource.getPlayer() : null;
                    logger.info("Calling method: collect");
                    getFunctionOutput(bot == null ? "Bot not found."
                            : CollectTool.collectNearby(bot).join());
                }
                case "combat" -> {
                    ServerPlayer bot = (botSource != null) ? botSource.getPlayer() : null;
                    logger.info("Calling method: combat");
                    getFunctionOutput(bot == null ? "Bot not found."
                            : CombatTool.combat(bot).join());
                }
                case "trade" -> {
                    ServerPlayer bot = (botSource != null) ? botSource.getPlayer() : null;
                    logger.info("Calling method: trade (inventory report)");
                    getFunctionOutput(bot == null ? "Bot not found."
                            : TradeTool.inventory(bot).join());
                }
                default -> logger.warn("Unknown function: {}", functionName);
            }

            logger.info("✓ Function {} execution completed", functionName);
            reportCompletion(functionName, paramMap);
        });
    }

    /**
     * Execute a Plan generated by the Planner.
     * Converts planned steps into sequential function calls.
     */
    public static CompletableFuture<Boolean> executePlan(Plan plan, ActionLogWriter logWriter, State initialState) {
        if (plan == null || plan.isEmpty()) {
            logger.warn("Cannot execute empty plan");
            return CompletableFuture.completedFuture(false);
        }

        if (botSource == null) {
            logger.error("Cannot execute plan: botSource is null! Bot not initialized properly.");
            return CompletableFuture.completedFuture(false);
        }

        logger.info("Executing plan: {}", plan.planId);
        logger.info("Plan has {} steps with total score: {}", plan.length(), plan.getTotalScore());

        // Show each atomic step above the bot's head (e.g. "Paul [mining 2/4]").
        // This covers BOTH the /bot plan path (handleUserGoal → executePlan) and
        // the autonomous path (HybridPlanner.executeGoal → executePlan).
        String statusBotName = botSource.getPlayer() != null
                ? botSource.getPlayer().getName().getString() : null;

        // Create SharedState for inter-step communication
        Map<String, Object> sharedState = new java.util.concurrent.ConcurrentHashMap<>();

        // Debug: Log all step names
        for (int i = 0; i < plan.steps.size(); i++) {
            PlannedStep step = plan.steps.get(i);
            logger.debug("  Step {}: {} (byte: {})", i + 1, step.actionName, step.actionId & 0xFF);
        }

        // ✅ Execute steps SEQUENTIALLY with blocking and state verification
        CompletableFuture<Void> sequentialExecution = CompletableFuture.completedFuture(null);

        for (int i = 0; i < plan.steps.size(); i++) {
            PlannedStep step = plan.steps.get(i);
            final int stepIndex = i;

            // Only a *movement* step hands work to a background driver (the
            // PathTracer), so only then must the next step wait for arrival.
            // Scoping it this way deliberately excludes a COMBAT pursuit
            // started inside a combat step, which would otherwise pin the plan
            // until the chase resolved.
            final boolean previousStepWasMovement = i > 0
                    && isMovementStep(plan.steps.get(i - 1).actionName);

            sequentialExecution = sequentialExecution.thenCompose(_void -> {
                // Hunger maintenance may suspend a busy plan between atomic
                // actions. Continue this exact plan after eating completes.
                FoodConsumptionTool.awaitResume(botSource.getPlayer());

                // Pause between atomic steps while a close threat is active, so
                // reactive combat (AutoFaceEntity) resolves before the next step
                // mutates the world. The plan resumes in place once the threat
                // is gone — the "interrupt → fight → continue from where it left
                // off" behaviour, at the step granularity.
                awaitThreatClear(botSource.getPlayer());

                // The previous step only *started* a walk; wait for the bot to
                // actually arrive before running this one. Without this the step
                // (mine/place/build/collect) executed mid-walk and failed its own
                // range check instead — `mineBlock` rejected every gather target
                // with "Target is more than five blocks away", so no wood was
                // ever collected.
                if (previousStepWasMovement) {
                    awaitNavigationSettled(botSource.getPlayer());
                }

                // Preemption: a higher-priority task (player "come to me", a
                // world event, a companion FOLLOW/STAY) was injected while this
                // plan ran. Abort between steps so the queue can run it; the
                // goal is re-enqueued by the caller (restart-at-goal-granularity
                // checkpoint — plans are short and idempotent enough to re-run).
                if (preemptionRequested) {
                    throw new PreemptionException();
                }

                // Get state BEFORE action
                State stateBefore = initialState; // TODO: Could update this per step

                // Convert PlannedStep to function call, resolving params from SharedState
                Map<String, String> params = convertStepToParams(step, sharedState);

                logger.info("🔧 Step {}/{}: Executing {} with params: {}",
                    stepIndex + 1, plan.steps.size(), step.actionName, params);

                if (statusBotName != null) {
                    BotStatusLabel.setStatus(statusBotName,
                            stepLabel(step.actionName, stepIndex + 1, plan.steps.size()));
                }

                return callFunction(step.actionName, params, sharedState)
                    .thenCompose(result -> {
                        // Add delay for game state to update (CRITICAL for sequential execution)
                        return CompletableFuture.runAsync(() -> {
                            try {
                                Thread.sleep(500); // Wait 500ms for game state update
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
                    })
                    .thenApply(_void2 -> {
                        // Verify the step actually achieved its goal
                        boolean verified = verifyStepOutcome(step, sharedState);

                        // Log execution
                        if (logWriter != null) {
                            logWriter.logStep(
                                plan.planId,
                                plan.goalId,
                                stateBefore,
                                stepIndex,
                                step,
                                verified ? "success" : "failed_verification",
                                verified ? 10.0 : -5.0,
                                false
                            );
                        }

                        if (verified) {
                            logger.info("✓ Step {}/{}: {} completed and verified",
                                       stepIndex + 1, plan.steps.size(), step.actionName);
                        } else {
                            logger.warn("⚠ Step {}/{}: {} completed but verification failed",
                                       stepIndex + 1, plan.steps.size(), step.actionName);
                            // If verification fails for critical action, abort
                            if (isCriticalAction(step.actionName)) {
                                throw new RuntimeException("Critical action verification failed: " + step.actionName);
                            }
                        }

                        return (Void) null;
                    })
                    .exceptionally(ex -> {
                        // Log failure
                        if (logWriter != null) {
                            logWriter.logStep(
                                plan.planId,
                                plan.goalId,
                                stateBefore,
                                stepIndex,
                                step,
                                "failed: " + ex.getMessage(),
                                -10.0, // Penalty for failure
                                false
                            );
                        }
                        logger.error("✗ Step {}/{}: {} failed: {}",
                                    stepIndex + 1, plan.steps.size(), step.actionName, ex.getMessage());

                        // For critical actions, throw to abort plan
                        if (isCriticalAction(step.actionName)) {
                            logger.error("✗ Critical action failed, aborting plan");
                            throw new RuntimeException("Critical action failed: " + step.actionName);
                        }

                        return null;
                    });
            });
        }

        return sequentialExecution.handle((result, ex) -> {
            if (statusBotName != null) {
                BotStatusLabel.clear(statusBotName);
            }
            if (ex != null) {
                if (ex instanceof PreemptionException || ex.getCause() instanceof PreemptionException) {
                    logger.info("Plan {} preempted by a higher-priority task", plan.planId);
                } else {
                    logger.error("Plan execution failed: {}", ex.getMessage());
                }
                return false;
            } else {
                logger.info("✓ Plan {} executed successfully", plan.planId);
                return true;
            }
        });
    }

    /** Map an atomic action name to a short human verb, plus step progress. */
    private static String stepLabel(String actionName, int index, int total) {
        String verb = switch (actionName.toLowerCase()) {
            case "goto", "movetocoordinates", "chartpathtoblock" -> "walking";
            case "searchblocks", "detectblocks" -> "searching";
            case "mineblock", "breakblock" -> "mining";
            case "placeblock" -> "placing blocks";
            case "build" -> "building";
            case "craft" -> "crafting";
            case "farm", "farmtill", "farmplant", "farmharvest" -> "farming";
            case "combat" -> "fighting";
            case "collect" -> "collecting";
            case "terraform" -> "terraforming";
            case "searchflatsite" -> "finding a site";
            case "look", "turn" -> "looking";
            case "trade" -> "trading";
            case "equiparmor" -> "equipping";
            default -> actionName.toLowerCase();
        };
        if (total > 1) {
            return verb + " (" + index + "/" + total + ")";
        }
        return verb;
    }

    /**
     * Block the current plan-execution thread while a close hostile is active,
     * yielding to {@link AutoFaceEntity}'s reactive combat. Polls every 500ms so
     * the plan resumes promptly once the threat is cleared. A plan in progress
     * therefore never runs a world-mutating step (place/craft/mine) while a mob
     * is in the bot's face — it pauses, the bot fights, then it continues.
     */
    private static void awaitThreatClear(ServerPlayer bot) {
        if (bot == null) return;
        while (AutoFaceEntity.isCloseThreatActive(bot)) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Maximum time a plan waits for an asynchronous movement step to finish.
     *
     * <p>Generous on purpose: on the DGX Spark the server routinely runs tens of
     * ticks behind, which makes a 20-block walk take far longer than its nominal
     * duration.
     */
    private static final long NAVIGATION_SETTLE_TIMEOUT_MS = 60_000L;

    /** Poll interval for {@link #awaitNavigationSettled}. */
    private static final long NAVIGATION_SETTLE_POLL_MS = 250L;

    /**
     * Whether a step only <em>starts</em> asynchronous movement, so the step
     * after it must wait for the bot to arrive.
     */
    private static boolean isMovementStep(String actionName) {
        if (actionName == null) return false;
        return switch (actionName.toLowerCase()) {
            case "goto", "movetocoordinates", "chartpathtoblock" -> true;
            default -> false;
        };
    }

    /**
     * Block until an in-flight movement step has actually finished.
     *
     * <p>{@code goTo} returns as soon as its path is <em>accepted</em> — the
     * PathTracer then drives the bot over subsequent ticks — so without this wait
     * the next step executes while the bot is still walking. That silently broke
     * every gather trip: {@code mineBlock} ran mid-walk and was rejected by
     * {@code MiningTool.validateStart} with "Target is more than five blocks
     * away", so no wood was ever collected and the goal failed while looking like
     * a mining bug.
     *
     * <p>Uses {@link NavigationService#isNavigating} rather than {@code
     * isActivelyNavigating}: a <em>suspended</em> session has not arrived either,
     * it is merely paused (by a threat or a tool), and resuming it is the
     * PathTracer's job. Waiting for the session to leave {@code SESSIONS} covers
     * both arrival and an outright failure (no path / stuck), so a failed walk
     * fails the following step instead of hanging the plan.
     */
    private static void awaitNavigationSettled(ServerPlayer bot) {
        if (bot == null) return;
        java.util.UUID botId = bot.getUUID();
        if (!NavigationService.isNavigating(botId)) return;

        long deadline = System.currentTimeMillis() + NAVIGATION_SETTLE_TIMEOUT_MS;
        while (NavigationService.isNavigating(botId) && System.currentTimeMillis() < deadline) {
            // Let a queued higher-priority task abort this plan rather than
            // keeping the worker pinned until the walk ends.
            if (preemptionRequested) return;
            try {
                Thread.sleep(NAVIGATION_SETTLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (NavigationService.isNavigating(botId)) {
            logger.warn("Movement step still in flight after {}s — continuing the plan in place",
                    NAVIGATION_SETTLE_TIMEOUT_MS / 1000);
        }
    }

    /**
     * Convert a PlannedStep to parameter map for function calling WITH SharedState resolution.
     */
    private static Map<String, String> convertStepToParams(PlannedStep step, Map<String, Object> state) {
        Map<String, String> params = new HashMap<>();

        // Map action to parameters based on action name
        String actionName = step.actionName.toLowerCase();

        // Parse params string into array (comma-separated or JSON array)
        String[] paramArray = new String[0];
        if (step.params != null && !step.params.trim().isEmpty()) {
            String trimmedParams = step.params.trim();
            if (trimmedParams.startsWith("[") && trimmedParams.endsWith("]")) {
                // JSON array format: ["x", "y", "z"]
                String content = trimmedParams.substring(1, trimmedParams.length() - 1);
                if (!content.trim().isEmpty()) {
                    paramArray = content.split(",");
                    for (int i = 0; i < paramArray.length; i++) {
                        paramArray[i] = paramArray[i].trim().replaceAll("^\"|\"$", "");
                    }
                }
            } else {
                // Simple comma-separated: x,y,z
                paramArray = trimmedParams.split(",");
                for (int i = 0; i < paramArray.length; i++) {
                    paramArray[i] = paramArray[i].trim();
                }
            }
        }

        // Normalize action name for comparison (case-insensitive)
        switch (actionName) {
            case "goto":
            case "movetocoordinates":
                // Prefer a build site found by searchFlatSite; then a block found
                // by searchBlocks; then explicit params.
                if (SharedStateUtils.getValue(state, "found_site_x") != null) {
                    int sx = (int) SharedStateUtils.getValue(state, "found_site_x");
                    int sy = (int) SharedStateUtils.getValue(state, "found_site_y");
                    int sz = (int) SharedStateUtils.getValue(state, "found_site_z");
                    params.put("x", String.valueOf(sx));
                    params.put("y", String.valueOf(sy));
                    params.put("z", String.valueOf(sz));
                    params.put("sprint", "true");
                    logger.info("🔗 Resolved goTo params from build site SharedState: ({}, {}, {})", sx, sy, sz);
                } else if (SharedStateUtils.getValue(state, "lastFlatSite.x") != null) {
                    // Alias registered by the searchFlatSite tool / LLM pipelines.
                    int sx = (int) SharedStateUtils.getValue(state, "lastFlatSite.x");
                    int sy = (int) SharedStateUtils.getValue(state, "lastFlatSite.y");
                    int sz = (int) SharedStateUtils.getValue(state, "lastFlatSite.z");
                    params.put("x", String.valueOf(sx));
                    params.put("y", String.valueOf(sy));
                    params.put("z", String.valueOf(sz));
                    params.put("sprint", "true");
                    logger.info("🔗 Resolved goTo params from lastFlatSite alias: ({}, {}, {})", sx, sy, sz);
                } else if (SharedStateUtils.getValue(state, "found_block_x") != null) {
                    int blockX = (int) SharedStateUtils.getValue(state, "found_block_x");
                    int blockY = (int) SharedStateUtils.getValue(state, "found_block_y");
                    int blockZ = (int) SharedStateUtils.getValue(state, "found_block_z");

                    // Stand NEXT TO the block, not blindly at +1 X: a blind offset
                    // can land on open air (cliff edge), which navigation then
                    // normalises to the cliff top, stranding the bot above the
                    // target. Compute a standable cell at the block's own level.
                    net.minecraft.core.BlockPos target = new net.minecraft.core.BlockPos(blockX, blockY, blockZ);
                    net.minecraft.core.BlockPos stand = botSource != null && botSource.getPlayer() != null
                            ? net.shasankp000.PlayerUtils.StandPosition.findStandNear(botSource.getPlayer(), target)
                            : null;
                    if (stand == null) {
                        stand = target.offset(1, 0, 0); // fall back to the old heuristic
                    }

                    params.put("x", String.valueOf(stand.getX()));
                    params.put("y", String.valueOf(stand.getY()));
                    params.put("z", String.valueOf(stand.getZ()));
                    params.put("sprint", "true");
                    logger.info("🔗 Resolved goTo params from SharedState: ({}, {}, {}) (stand for block {})",
                            stand.getX(), stand.getY(), stand.getZ(), target.toShortString());
                } else if (paramArray.length >= 3) {
                    params.put("x", paramArray[0]);
                    params.put("y", paramArray[1]);
                    params.put("z", paramArray[2]);
                    params.put("sprint", paramArray.length >= 4 ? paramArray[3] : "true");
                }
                break;

            case "mineblock":
            case "breakblock":
                // Try to get coords from SharedState first
                if (SharedStateUtils.getValue(state, "found_block_x") != null) {
                    int blockX = (int) SharedStateUtils.getValue(state, "found_block_x");
                    int blockY = (int) SharedStateUtils.getValue(state, "found_block_y");
                    int blockZ = (int) SharedStateUtils.getValue(state, "found_block_z");
                    params.put("targetX", String.valueOf(blockX));
                    params.put("targetY", String.valueOf(blockY));
                    params.put("targetZ", String.valueOf(blockZ));
                    logger.info("🔗 Resolved mineBlock params from SharedState: ({}, {}, {})", blockX, blockY, blockZ);
                } else if (paramArray.length >= 3) {
                    params.put("targetX", paramArray[0]);
                    params.put("targetY", paramArray[1]);
                    params.put("targetZ", paramArray[2]);
                }
                break;

            case "placeblock":
                if (paramArray.length >= 4) {
                    params.put("targetX", paramArray[0]);
                    params.put("targetY", paramArray[1]);
                    params.put("targetZ", paramArray[2]);
                    params.put("blockType", paramArray[3]);
                } else if (paramArray.length >= 1) {
                    params.put("blockType", paramArray[0]);
                }
                break;

            case "detectblocks":
                // Use found_block_type from SharedState if available
                if (SharedStateUtils.getValue(state, "found_block_type") != null) {
                    params.put("blockType", (String) SharedStateUtils.getValue(state, "found_block_type"));
                    logger.info("🔗 Resolved detectBlocks blockType from SharedState");
                } else if (paramArray.length >= 1) {
                    params.put("blockType", paramArray[0]);
                }
                break;

            case "searchblocks":
                if (paramArray.length >= 4) {
                    params.put("blockType", paramArray[0]);
                    params.put("initialRadius", paramArray[1]);
                    params.put("maxRadius", paramArray[2]);
                    params.put("radiusIncrement", paramArray[3]);
                } else if (paramArray.length >= 1) {
                    params.put("blockType", paramArray[0]);
                    // Set defaults
                    params.put("initialRadius", "10");
                    params.put("maxRadius", "100");
                    params.put("radiusIncrement", "20");
                }
                break;

            case "craft":
                if (paramArray.length >= 2) {
                    params.put("item", paramArray[0]);
                    params.put("count", paramArray[1]);
                } else if (paramArray.length >= 1) {
                    params.put("item", paramArray[0]);
                    params.put("count", "1");
                }
                break;

            case "farmtill":
            case "farmplant":
            case "farmharvest":
                if (paramArray.length >= 3) {
                    params.put("x", paramArray[0]);
                    params.put("y", paramArray[1]);
                    params.put("z", paramArray[2]);
                }
                if ("farmplant".equals(actionName) && paramArray.length >= 4) {
                    params.put("seed", paramArray[3]);
                }
                break;

            case "farm":
                if (paramArray.length >= 1) {
                    params.put("seed", paramArray[0]);
                } else {
                    params.put("seed", "wheat");
                }
                break;

            case "build":
                if (paramArray.length >= 2) {
                    params.put("structure", paramArray[0]);
                    params.put("blockType", paramArray[1]);
                } else if (paramArray.length >= 1) {
                    params.put("structure", paramArray[0]);
                    params.put("blockType", "minecraft:oak_planks");
                }
                break;

            case "searchflatsite":
            case "terraform":
                if (paramArray.length >= 1) {
                    params.put("structure", paramArray[0]);
                } else {
                    params.put("structure", "shelter");
                }
                break;

            case "combat":
            case "trade":
            case "collect":
                // No parameters: the tools locate their own target / report
                // the bot's own inventory / collect nearby drops.
                break;

            case "turn":
                if (paramArray.length >= 1) {
                    params.put("direction", paramArray[0]);
                }
                break;

            case "look":
                if (paramArray.length >= 1) {
                    params.put("cardinalDirection", paramArray[0]);
                }
                break;

            case "websearch":
                if (paramArray.length >= 1) {
                    params.put("query", paramArray[0]);
                }
                break;

            default:
                logger.debug("No special parameter mapping for action: {}", actionName);
        }

        return params;
    }

    /**
     * Verify that a step achieved its expected outcome based on SharedState changes.
     */
    private static boolean verifyStepOutcome(PlannedStep step, Map<String, Object> sharedState) {
        String actionName = step.actionName.toLowerCase();

        switch (actionName) {
            case "searchblocks":
                // Verify searchBlocks actually found something
                Boolean searchSuccess = (Boolean) SharedStateUtils.getValue(sharedState, "search_success");
                if (searchSuccess != null && searchSuccess) {
                    logger.info("✓ searchBlocks verification: block found in SharedState");
                    return true;
                } else {
                    logger.warn("✗ searchBlocks verification failed: no block found");
                    return false;
                }

            case "goto":
            case "movetocoordinates":
                // Report the walk's ACTUAL outcome instead of assuming success.
                //
                // This used to return true unconditionally, on the reasoning that
                // goTo is asynchronous so a distance check would fire mid-walk.
                // That reasoning is stale: GoTo.goTo is now a blocking adapter
                // (NavigationService.navigate().get(5, MINUTES)) for the plan
                // threads, and it reports either "Bot moved to position ..." or
                // "Navigation <status> at ... - <reason>". Treating a failed walk
                // as verified let the plan carry on to act at a place the bot
                // never reached — every gather trip then died in mineBlock with
                // "Target is more than five blocks away" / "Player moved out of
                // mining range", which read as mining bugs.
                //
                // goTo is a critical action, so an honest failure here aborts the
                // plan (and the autonomous layer backs the goal off) rather than
                // flailing at an unreachable target.
                if (lastFunctionOutput == null) {
                    logger.info("✓ goTo verification: no result recorded, assuming accepted");
                    return true;
                }
                if (lastFunctionOutput.startsWith("Bot moved to position")) {
                    logger.info("✓ goTo verification: {}", lastFunctionOutput);
                    return true;
                }
                if (lastFunctionOutput.startsWith("Navigation")
                        || lastFunctionOutput.startsWith("Failed to execute goTo")) {
                    logger.warn("✗ goTo verification failed: {}", lastFunctionOutput);
                    return false;
                }
                // A server-thread caller gets "Bot navigation started" (genuinely
                // asynchronous) — arrival is the PathTracer's responsibility there.
                logger.info("✓ goTo verification: {} (asynchronous movement)", lastFunctionOutput);
                return true;

            case "mineblock":
            case "breakblock":
                // Verify block was actually broken by checking if it still exists
                Integer blockX = (Integer) SharedStateUtils.getValue(sharedState, "found_block_x");
                Integer blockY = (Integer) SharedStateUtils.getValue(sharedState, "found_block_y");
                Integer blockZ = (Integer) SharedStateUtils.getValue(sharedState, "found_block_z");

                if (blockX != null && blockY != null && blockZ != null && botSource != null) {
                    MinecraftServer server = botSource.getServer();
                    if (server != null) {
                        ServerLevel world = server.overworld();
                        BlockPos blockPos = new BlockPos(blockX, blockY, blockZ);
                        Block block = world.getBlockState(blockPos).getBlock();

                        // Check if block is now air (successfully mined)
                        if (block == Blocks.AIR) {
                            logger.info("✓ mineBlock verification: block successfully removed");
                            return true;
                        }
                        // The block still being there is the *symptom*; name the
                        // cause when the mining tool reported one. Without this the
                        // log said "block still exists" for a mine that never
                        // started (e.g. "Target is more than five blocks away"),
                        // which reads as a world problem instead of a range one.
                        if (lastFunctionOutput != null && lastFunctionOutput.contains("Failed to mine")) {
                            logger.warn("✗ mineBlock verification failed: {}", lastFunctionOutput);
                        } else {
                            logger.warn("✗ mineBlock verification failed: block still exists at "
                                    + blockPos.toShortString());
                        }
                        return false;
                    }
                }
                logger.info("✓ mineBlock verification: no target in SharedState, assuming success");
                return true;

            case "detectblocks":
                // DetectBlocks can succeed or fail - assume success for now
                // TODO: Could check if detectBlocks actually detected the target
                logger.info("✓ detectBlocks verification: assuming success (TODO: verify detection)");
                return true;

            case "build":
                // StructureBuilder returns "✅ Built ..." on success and
                // "❌ Build stopped after N/M blocks: ..." when a placement
                // fails. Without this case a build that placed zero blocks fell
                // through to `default`, which reported it as verified — a false
                // success that hid the failure from the planner and the
                // autonomous-learning reward.
                //
                // Read lastFunctionOutput (not functionOutput): reportCompletion
                // clears functionOutput to null after relaying it to chat, so
                // reading functionOutput here saw null even on a successful
                // build and wrongly aborted the plan as critical.
                if (lastFunctionOutput != null && lastFunctionOutput.startsWith("✅")) {
                    logger.info("✓ build verification: {}", lastFunctionOutput);
                    return true;
                }
                logger.warn("✗ build verification failed: {}", lastFunctionOutput);
                return false;

            case "searchflatsite":
                // searchFlatSite stores found_site_x/y/z on success.
                if (SharedStateUtils.getValue(sharedState, "found_site_x") != null) {
                    logger.info("✓ searchFlatSite verification: site found");
                    return true;
                }
                logger.warn("✗ searchFlatSite verification failed: no site found");
                return false;

            case "terraform":
                // terraform returns "✅ Terraformed site (...)" on success.
                // Same null-read hazard as build: read lastFunctionOutput.
                if (lastFunctionOutput != null && lastFunctionOutput.startsWith("✅")) {
                    logger.info("✓ terraform verification: {}", lastFunctionOutput);
                    return true;
                }
                logger.warn("✗ terraform verification failed: {}", lastFunctionOutput);
                return false;

            default:
                // For other actions, assume success if no exception was thrown
                logger.debug("✓ {} verification: default success", actionName);
                return true;
        }
    }

    /**
     * Convert a PlannedStep to parameter map for function calling (legacy method without SharedState).
     */
    private static Map<String, String> convertStepToParams(PlannedStep step) {
        Map<String, String> params = new HashMap<>();

        // Map action to parameters based on action name
        String actionName = step.actionName.toLowerCase();

        // Parse params string into array (comma-separated or JSON array)
        String[] paramArray = new String[0];
        if (step.params != null && !step.params.trim().isEmpty()) {
            String trimmedParams = step.params.trim();
            if (trimmedParams.startsWith("[") && trimmedParams.endsWith("]")) {
                // JSON array format: ["x", "y", "z"]
                String content = trimmedParams.substring(1, trimmedParams.length() - 1);
                if (!content.trim().isEmpty()) {
                    paramArray = content.split(",");
                    for (int i = 0; i < paramArray.length; i++) {
                        paramArray[i] = paramArray[i].trim().replaceAll("^\"|\"$", "");
                    }
                }
            } else {
                // Simple comma-separated: x,y,z
                paramArray = trimmedParams.split(",");
                for (int i = 0; i < paramArray.length; i++) {
                    paramArray[i] = paramArray[i].trim();
                }
            }
        }

        // Normalize action name for comparison (case-insensitive)
        switch (actionName) {
            case "goto":
            case "movetocoordinates":
                if (paramArray.length >= 3) {
                    params.put("x", paramArray[0]);
                    params.put("y", paramArray[1]);
                    params.put("z", paramArray[2]);
                    params.put("sprint", paramArray.length >= 4 ? paramArray[3] : "true");
                }
                break;

            case "mineblock":
            case "breakblock":
                if (paramArray.length >= 3) {
                    params.put("targetX", paramArray[0]);
                    params.put("targetY", paramArray[1]);
                    params.put("targetZ", paramArray[2]);
                }
                break;

            case "placeblock":
                if (paramArray.length >= 4) {
                    params.put("targetX", paramArray[0]);
                    params.put("targetY", paramArray[1]);
                    params.put("targetZ", paramArray[2]);
                    params.put("blockType", paramArray[3]);
                } else if (paramArray.length >= 1) {
                    params.put("blockType", paramArray[0]);
                }
                break;

            case "detectblocks":
                if (paramArray.length >= 1) {
                    params.put("blockType", paramArray[0]);
                }
                break;

            case "searchblocks":
                if (paramArray.length >= 4) {
                    params.put("blockType", paramArray[0]);
                    params.put("initialRadius", paramArray[1]);
                    params.put("maxRadius", paramArray[2]);
                    params.put("radiusIncrement", paramArray[3]);
                } else if (paramArray.length >= 1) {
                    params.put("blockType", paramArray[0]);
                    // Set defaults
                    params.put("initialRadius", "10");
                    params.put("maxRadius", "100");
                    params.put("radiusIncrement", "20");
                }
                break;

            case "turn":
                if (paramArray.length >= 1) {
                    params.put("direction", paramArray[0]);
                } else {
                    params.put("direction", "left"); // Default
                }
                break;

            case "look":
                if (paramArray.length >= 1) {
                    params.put("cardinalDirection", paramArray[0]);
                } else {
                    params.put("cardinalDirection", "north"); // Default
                }
                break;

            case "websearch":
                if (paramArray.length >= 1) {
                    params.put("query", paramArray[0]);
                }
                break;

            // Actions with no parameters (handled by bot commands)
            case "eat":
            case "attack":
            case "shoot":
            case "evade":
            case "retreat":
            case "shield":
            case "combat":
            case "trade":
            case "collect":
            case "gethealthlevel":
            case "gethungerlevel":
            case "getoxygenlevel":
                // No parameters needed - these are simple bot commands
                break;

            default:
                // If action not recognized, log warning but don't fail
                // The action might still be valid in the function caller
                logger.debug("No parameter mapping for action: {}", actionName);
        }

        return params;
    }

    /**
     * Determine if an action is critical (plan should abort if it fails).
     * Critical actions are those that subsequent steps depend on.
     */
    private static boolean isCriticalAction(String actionName) {
        if (actionName == null) return false;

        return switch (actionName.toLowerCase()) {
            case "searchblocks" -> true;
            // A build that cannot place its blocks has produced nothing; treat it
            // as critical so the plan fails loudly instead of reporting success.
            case "build" -> true;
            // goTo now fails hard when a coordinate placeholder cannot be
            // resolved (see resolveCoord). Treat it as critical so the plan
            // aborts there instead of continuing with the bot somewhere
            // arbitrary.
            case "goto", "movetocoordinates" -> true;
            default -> false;
        };
    }
}
