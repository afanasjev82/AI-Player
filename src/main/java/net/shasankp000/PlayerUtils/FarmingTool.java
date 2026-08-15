package net.shasankp000.PlayerUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.shasankp000.Entity.LookController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Farming tool (Phase C): find a tillable spot near the bot, till it, plant a
 * seed, and (optionally) harvest a mature crop.
 *
 * <p>All operations locate their target relative to the bot's <b>live</b>
 * position (not a cached {@code State} snapshot), so the farm skill works even
 * after the bot has moved.
 */
public final class FarmingTool {
    private static final Logger LOGGER = LoggerFactory.getLogger("farming-tool");

    private FarmingTool() {}

    /**
     * High-level farm action: find nearby dirt/grass, till it, and plant the
     * seed. Returns a descriptive result string.
     */
    public static CompletableFuture<String> farm(ServerPlayer bot, String seedType) {
        return runOnServer(bot, () -> {
            Item seed = resolveSeed(seedType);
            if (seed == null) return "❌ Unknown seed type: " + seedType;

            ItemStack seedStack = findItem(bot, s -> s.getItem() == seed);
            if (seedStack == null) return "❌ No " + seed.getDescriptionId() + " in inventory.";

            ItemStack hoe = findItem(bot, FarmingTool::isHoe);
            if (hoe == null) return "❌ No hoe in inventory.";

            BlockPos dirt = findTillable(bot);
            if (dirt == null) return "❌ No dirt/grass within 6 blocks to farm.";

            // Till.
            String tillResult = useOn(bot, dirt, hoe);
            if (!tillResult.startsWith("✅")) return tillResult;

            // Plant on the newly-tilled farmland.
            return useOn(bot, dirt, seedStack);
        });
    }

    /** Till a specific block (dirt/grass → farmland). */
    public static CompletableFuture<String> till(ServerPlayer bot, BlockPos pos) {
        return runOnServer(bot, () -> {
            ItemStack hoe = findItem(bot, FarmingTool::isHoe);
            if (hoe == null) return "❌ No hoe in inventory.";
            return useOn(bot, pos, hoe);
        });
    }

    /** Plant a seed at a specific (tilled) block. */
    public static CompletableFuture<String> plant(ServerPlayer bot, BlockPos pos, String seedType) {
        return runOnServer(bot, () -> {
            Item seed = resolveSeed(seedType);
            if (seed == null) return "❌ Unknown seed type: " + seedType;
            ItemStack seedStack = findItem(bot, s -> s.getItem() == seed);
            if (seedStack == null) return "❌ No " + seed.getDescriptionId() + " in inventory.";
            return useOn(bot, pos, seedStack);
        });
    }

    /** Harvest a mature crop at {@code pos}. */
    public static CompletableFuture<String> harvest(ServerPlayer bot, BlockPos pos) {
        return runOnServer(bot, () -> {
            BlockState state = bot.level().getBlockState(pos);
            if (!(state.getBlock() instanceof CropBlock crop)) {
                return "❌ No crop at " + pos + ".";
            }
            if (!crop.isMaxAge(state)) {
                return "❌ Crop not mature yet at " + pos + ".";
            }
            bot.gameMode.destroyBlock(pos);
            return "✅ Harvested crop at " + pos + ".";
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Find the nearest dirt/grass block within 6 blocks (horizontal). */
    private static BlockPos findTillable(ServerPlayer bot) {
        BlockPos feet = bot.blockPosition();
        for (int r = 0; r <= 6; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos p = feet.offset(dx, dy, dz);
                        Block b = bot.level().getBlockState(p).getBlock();
                        if (b == Blocks.DIRT || b == Blocks.GRASS_BLOCK) {
                            return p;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Use {@code item} on the top face of {@code pos} via the vanilla path. */
    private static String useOn(ServerPlayer bot, BlockPos pos, ItemStack item) {
        ensureInHand(bot, item);

        LookController.faceBlock(bot, pos);

        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(pos),
                Direction.UP,
                pos,
                false
        );

        ItemStack held = bot.getItemInHand(InteractionHand.MAIN_HAND);
        bot.gameMode.useItemOn(bot, bot.level(), held, InteractionHand.MAIN_HAND, hit);
        return "✅ Used " + item.getItemName().getString() + " at " + pos + ".";
    }

    /** Move {@code item} into a hotbar slot and select it, so it is held. */
    private static void ensureInHand(ServerPlayer bot, ItemStack item) {
        var inv = bot.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack slot = inv.getItem(i);
            if (!slot.isEmpty() && slot.getItem() == item.getItem()) {
                inv.setSelectedSlot(i);
                return;
            }
        }
        for (int src = 9; src < inv.getContainerSize(); src++) {
            ItemStack stack = inv.getItem(src);
            if (!stack.isEmpty() && stack.getItem() == item.getItem()) {
                int empty = -1;
                for (int h = 0; h < 9; h++) {
                    if (inv.getItem(h).isEmpty()) { empty = h; break; }
                }
                if (empty >= 0) {
                    inv.setItem(empty, stack);
                    inv.setItem(src, ItemStack.EMPTY);
                    inv.setSelectedSlot(empty);
                    return;
                }
            }
        }
    }

    private static boolean isHoe(ItemStack stack) {
        String id = stack.getItem().toString();
        return id.endsWith("_hoe");
    }

    private static Item resolveSeed(String seedType) {
        if (seedType == null) return Items.WHEAT_SEEDS;
        String s = seedType.trim().toLowerCase();
        return switch (s) {
            case "wheat", "wheat_seeds", "wheat seeds", "minecraft:wheat_seeds" -> Items.WHEAT_SEEDS;
            case "carrot", "carrots", "minecraft:carrot" -> Items.CARROT;
            case "potato", "potatoes", "minecraft:potato" -> Items.POTATO;
            case "beetroot", "beetroot_seeds", "minecraft:beetroot_seeds" -> Items.BEETROOT_SEEDS;
            default -> null;
        };
    }

    private static ItemStack findItem(ServerPlayer bot, java.util.function.Predicate<ItemStack> pred) {
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (!stack.isEmpty() && pred.test(stack)) return stack;
        }
        return null;
    }

    private static CompletableFuture<String> runOnServer(ServerPlayer bot, java.util.concurrent.Callable<String> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (bot == null || !bot.isAlive() || bot.hasDisconnected()) {
                    return "❌ Bot is unavailable.";
                }
                var server = bot.createCommandSourceStack().getServer();
                if (server.isSameThread()) return task.call();
                CompletableFuture<String> future = new CompletableFuture<>();
                server.execute(() -> {
                    try {
                        future.complete(task.call());
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });
                return future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.error("Farming operation failed: {}", e.getMessage(), e);
                return "❌ Farming failed: " + e.getMessage();
            }
        });
    }
}
