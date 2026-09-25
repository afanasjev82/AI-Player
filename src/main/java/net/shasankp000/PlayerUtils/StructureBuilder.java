package net.shasankp000.PlayerUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.PathFinding.GoTo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Structure building (Phase C): lays out a block pattern relative to the bot
 * and places each block via {@link BlockPlacementTool}.
 *
 * <p>Supports a small catalog of simple structures the bot can build
 * deterministically:
 * <ul>
 *   <li>{@code wall}  — a 1-thick wall, {@code length} wide × {@code height} tall.</li>
 *   <li>{@code shelter}— a 3×3×3 hollow box (roof + walls, door gap on one side).</li>
 *   <li>{@code room}  — a larger 5×4×5 hollow box (roof + walls, door gap).</li>
 * </ul>
 *
 * <p>The bot must have the building material in inventory. Each block is placed
 * sequentially; if any placement fails (missing material), the build stops and
 * reports how many blocks were placed.
 */
public final class StructureBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("structure-builder");

    /**
     * Reach allowance for laying out a structure from a single bot position.
     *
     * <p>A 5-wide footprint spreads its far cells ~7 blocks diagonally from a
     * bot standing at the structure's origin, which exceeds a player-like
     * 5-block reach. The builder places a known layout rather than mimicking a
     * player's arm, so it uses a larger limit instead of failing mid-build.
     */
    private static final double BUILD_REACH = 16.0;

    /** How many alternative sites to try before falling back to a smaller structure. */
    private static final int MAX_SITE_ATTEMPTS = 3;

    private StructureBuilder() {}

    /** Build {@code structureName} (wall/shelter/room) near the bot using {@code blockType}. */
    public static CompletableFuture<String> build(ServerPlayer bot, String structureName, String blockType) {
        return buildAt(bot, structureName, blockType, null);
    }

    /**
     * Build a structure anchored at an explicit {@code origin} (the bot's
     * standing block). If {@code origin} is null, the bot's current position is
     * used.
     */
    public static CompletableFuture<String> buildAt(ServerPlayer bot, String structureName,
                                                     String blockType, BlockPos origin) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (bot == null || !bot.isAlive() || bot.hasDisconnected()) {
                    return "❌ Bot is unavailable.";
                }
                BlockPos anchor = origin != null ? origin : bot.blockPosition();
                return doBuildAt(bot, structureName, blockType, anchor);
            } catch (Exception e) {
                LOGGER.error("Build failed: {}", e.getMessage(), e);
                return "❌ Build failed: " + e.getMessage();
            }
        });
    }

    /**
     * Gauntlet-style build: try the current site; on failure search for a
     * nearby flat site, navigate there, and retry. Falls back to a smaller
     * structure when even a new site won't work. Deterministic — no LLM in the
     * loop — so it is fast and never blocks on a slow/timing-out model.
     *
     * <p>Recovery progress is reported to in-game chat so a watching player can
     * follow along. When the optional LLM build verifier is enabled
     * ({@code -Daiplayer.llmBuildVerifier=true}), a single LLM consultation is
     * used as the final word before giving up.
     */
    public static CompletableFuture<String> buildWithRecovery(ServerPlayer bot, String structureName,
                                                              String blockType) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (bot == null || !bot.isAlive() || bot.hasDisconnected()) {
                    return "❌ Bot is unavailable.";
                }
                say(bot, "🔨 Building a " + structureName + "…");
                String result = doBuildAt(bot, structureName, blockType, bot.blockPosition());
                if (result.startsWith("✅")) return result;

                say(bot, "❌ Couldn't build here — searching for a flatter spot nearby…");
                LOGGER.warn("Build failed at current site ({}); searching for a flat site nearby", result);
                int[] dims = dimensions(structureName);
                if (dims == null) return result;

                // Retry on progressively wider search rings.
                for (int attempt = 1; attempt <= MAX_SITE_ATTEMPTS; attempt++) {
                    BlockPos site = SiteSurveyor.findFlatSite(bot, dims[0], dims[1], dims[2],
                            6 + attempt * 3);
                    if (site == null) {
                        LOGGER.warn("No flat site found (attempt {})", attempt);
                        continue;
                    }
                    String nav = goTo(bot, site);
                    if (!nav.contains("moved to position")) {
                        LOGGER.warn("Navigation to site {} failed: {}", site, nav);
                        continue;
                    }
                    say(bot, "🚶 Found a better spot — building there…");
                    result = doBuildAt(bot, structureName, blockType, bot.blockPosition());
                    if (result.startsWith("✅")) {
                        return result + " (relocated)";
                    }
                }

                // Fallback: build a smaller structure in place.
                String smaller = smallerStructure(structureName);
                if (smaller != null) {
                    say(bot, "🏠 Building a smaller " + smaller + " instead…");
                    LOGGER.warn("Falling back from {} to {}", structureName, smaller);
                    result = doBuildAt(bot, smaller, blockType, bot.blockPosition());
                    if (result.startsWith("✅")) return result + " (built " + smaller + " instead)";
                }

                // LLM-in-the-loop experiment (opt-in): one final consultation.
                if (BuildVerifier.isEnabled()) {
                    BuildVerifier.Advice advice = BuildVerifier.consult(structureName, blockType, result);
                    say(bot, "🤖 Asking the LLM what to try next…");
                    switch (advice) {
                        case SMALLER -> {
                            if (smaller != null) {
                                String r2 = doBuildAt(bot, smaller, blockType, bot.blockPosition());
                                if (r2.startsWith("✅")) return r2 + " (LLM suggested " + smaller + ")";
                            }
                        }
                        case RETRY, DIFFERENT_BLOCK -> {
                            String r2 = doBuildAt(bot, structureName, blockType, bot.blockPosition());
                            if (r2.startsWith("✅")) return r2 + " (LLM suggested retry)";
                        }
                        case GIVE_UP -> { /* fall through and report the failure */ }
                    }
                }
                return result;
            } catch (Exception e) {
                LOGGER.error("Build failed: {}", e.getMessage(), e);
                return "❌ Build failed: " + e.getMessage();
            }
        });
    }

    /** Send a message from the bot to in-game chat (best-effort, never throws). */
    private static void say(ServerPlayer bot, String message) {
        try {
            ChatUtils.sendChatMessages(bot.createCommandSourceStack().withSuppressedOutput()
                    .withMaximumPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS),
                    message, false);
        } catch (Exception e) {
            LOGGER.warn("Failed to send build progress message: {}", e.getMessage());
        }
    }

    /** Clear (terraform) the footprint of a structure at the bot's site, without building. */
    public static CompletableFuture<String> terraform(ServerPlayer bot, String structureName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (bot == null || !bot.isAlive() || bot.hasDisconnected()) {
                    return "❌ Bot is unavailable.";
                }
                List<BlockPos> layout = layout(structureName, bot.blockPosition());
                if (layout == null) {
                    return "❌ Unknown structure: " + structureName
                            + " (supported: wall, shelter, room).";
                }
                int cleared = clearVolume(bot, layout);
                return "✅ Terraformed site (" + cleared + " blocks cleared).";
            } catch (Exception e) {
                LOGGER.error("Terraform failed: {}", e.getMessage(), e);
                return "❌ Terraform failed: " + e.getMessage();
            }
        });
    }

    /** Footprint dimensions {@code {x, z, height}} for a structure, or null if unknown. */
    public static int[] dimensions(String structureName) {
        return switch (structureName.toLowerCase()) {
            case "wall"    -> new int[]{5, 1, 3};
            case "shelter" -> new int[]{3, 3, 3};
            case "room"    -> new int[]{5, 5, 4};
            default        -> null;
        };
    }

    /** A smaller structure to fall back to when a build won't fit, or null. */
    private static String smallerStructure(String structureName) {
        return switch (structureName.toLowerCase()) {
            case "room"    -> "shelter";
            case "shelter" -> "wall";
            default        -> null;
        };
    }

    private static String doBuildAt(ServerPlayer bot, String structureName, String blockType, BlockPos origin) {
        List<BlockPos> layout = layout(structureName, origin);
        if (layout == null) {
            return "❌ Unknown structure: " + structureName
                    + " (supported: wall, shelter, room).";
        }

        // Level the site first. There is no terrain shape small enough for the
        // catalog structures to fit on unchanged, so instead of refusing to
        // build on uneven ground we clear the structure's own bounding volume:
        // every remaining block in there would otherwise fail placement with
        // "target position is already occupied". (An earlier version instead
        // lifted the layout above the highest block in the footprint, which on a
        // sloped cliff pushed the build out of reach and left it floating.)
        clearVolume(bot, layout);

        int placed = 0;
        for (BlockPos pos : layout) {
            // This method runs on the common ForkJoinPool (via build()'s
            // supplyAsync), the same thread context the single-block placeBlock
            // tool already uses successfully. Each placement is awaited
            // sequentially — no server-thread blocking, so no deadlock.
            String result = BlockPlacementTool.placeBlock(bot, pos, blockType, BUILD_REACH).join();
            if (!result.startsWith("✅")) {
                return "❌ Build stopped after " + placed + "/" + layout.size()
                        + " blocks: " + result;
            }
            placed++;
        }
        return "✅ Built " + structureName + " (" + placed + " blocks).";
    }

    private static String goTo(ServerPlayer bot, BlockPos site) {
        try {
            return GoTo.goTo(bot.createCommandSourceStack().withSuppressedOutput()
                    .withMaximumPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS),
                    site.getX(), site.getY(), site.getZ(), true);
        } catch (Exception e) {
            return "❌ Navigation failed: " + e.getMessage();
        }
    }

    /**
     * Clear every cell inside the bounding volume of {@code layout} (including
     * the hollow interior and the door gap, so the result is a clean box rather
     * than a box filled with whatever terrain was there).
     *
     * <p>Block edits are marshalled onto the server thread: this runs on the
     * common ForkJoinPool, and mutating the world off-thread is unsafe.
     *
     * @return the number of blocks cleared
     */
    private static int clearVolume(ServerPlayer bot, List<BlockPos> layout) {
        if (layout.isEmpty()) return 0;
        Level level = bot.level();
        MinecraftServer server = bot.level().getServer();

        int minX = layout.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int maxX = layout.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minY = layout.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int maxY = layout.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int minZ = layout.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxZ = layout.stream().mapToInt(BlockPos::getZ).max().orElseThrow();

        // Never clear the bot's own two body blocks, or it would fall.
        BlockPos botFeet = bot.blockPosition();
        BlockPos botHead = botFeet.above();

        AtomicInteger cleared = new AtomicInteger();
        runOnServer(server, () -> {
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (p.equals(botFeet) || p.equals(botHead)) continue;
                        if (!level.getBlockState(p).isAir()) {
                            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                            cleared.incrementAndGet();
                        }
                    }
                }
            }
        });
        LOGGER.info("Cleared build site {}..{} ({}x{}x{})",
                new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ),
                maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        return cleared.get();
    }

    /** Run {@code task} on the server thread and wait for it to finish. */
    private static void runOnServer(MinecraftServer server, Runnable task) {
        if (server == null || server.isSameThread()) {
            task.run();
            return;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        server.execute(() -> {
            try {
                task.run();
            } finally {
                done.complete(null);
            }
        });
        done.join();
    }

    /** Compute the block positions for a structure anchored near {@code origin}. */
    static List<BlockPos> layout(String name, BlockPos origin) {
        switch (name.toLowerCase()) {
            case "wall":
                return wall(origin, 5, 3);
            case "shelter":
                return shelter(origin, 3, 3);
            case "room":
                return shelter(origin, 5, 4);
            default:
                return null;
        }
    }

    /** A 1-thick wall {@code length} long × {@code height} tall, along +X. */
    private static List<BlockPos> wall(BlockPos origin, int length, int height) {
        List<BlockPos> blocks = new ArrayList<>();
        int baseY = origin.getY();
        for (int x = 0; x < length; x++) {
            for (int y = 0; y < height; y++) {
                blocks.add(new BlockPos(origin.getX() + x + 1, baseY + y, origin.getZ() + 1));
            }
        }
        return blocks;
    }

    /**
     * A hollow box {@code size}×{@code size} (footprint) × {@code height} tall,
     * with a 1-block door gap at the front (+Z face).
     */
    private static List<BlockPos> shelter(BlockPos origin, int size, int height) {
        List<BlockPos> blocks = new ArrayList<>();
        int bx = origin.getX() + 1;
        int by = origin.getY();
        int bz = origin.getZ() + 1;

        // Floor perimeter (leave interior empty), walls, and roof.
        for (int y = 0; y <= height; y++) {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    boolean isFloor = (y == 0);
                    boolean isRoof = (y == height);
                    boolean isWall = (x == 0 || x == size - 1 || z == 0 || z == size - 1);

                    if (isFloor || isRoof || isWall) {
                        // Door gap: skip the bottom two blocks on the +Z face center.
                        boolean door = (z == size - 1 && x == size / 2 && y >= 1 && y <= 2);
                        if (door) continue;
                        blocks.add(new BlockPos(bx + x, by + y, bz + z));
                    }
                }
            }
        }
        return blocks;
    }
}
