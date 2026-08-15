package net.shasankp000.PlayerUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.shasankp000.Entity.LookController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Farming tool (Phase C): till soil, plant seeds, and harvest mature crops.
 *
 * <p>Uses the same server-side {@code useItemOn} path as {@link BlockPlacementTool}
 * so planting a seed or tilling dirt behaves exactly as vanilla. Harvesting
 * breaks a mature {@link CropBlock} via {@code gameMode.destroyBlock}, which
 * yields drops into the bot's inventory.
 */
public final class FarmingTool {
    private static final Logger LOGGER = LoggerFactory.getLogger("farming-tool");

    private FarmingTool() {}

    /** Till the block at {@code pos} into farmland (must be dirt/grass). */
    public static CompletableFuture<String> till(ServerPlayer bot, BlockPos pos) {
        return runOnServer(bot, () -> {
            ItemStack hoe = findItem(bot, stack -> {
                Item i = stack.getItem();
                String id = i.toString();
                return id.contains("_hoe") || id.contains("hoe");
            });
            if (hoe == null) {
                return "❌ No hoe in inventory.";
            }
            return useOnBlock(bot, pos, hoe);
        });
    }

    /** Plant a seed at {@code pos} (must be tilled farmland). */
    public static CompletableFuture<String> plant(ServerPlayer bot, BlockPos pos, String seedType) {
        return runOnServer(bot, () -> {
            Item seed = resolveSeed(seedType);
            if (seed == null) {
                return "❌ Unknown seed type: " + seedType;
            }
            ItemStack seedStack = findItem(bot, stack -> stack.getItem() == seed);
            if (seedStack == null) {
                return "❌ No " + seed.getDescriptionId() + " in inventory.";
            }
            return useOnBlock(bot, pos, seedStack);
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

    /** Whether the block at {@code pos} is mature and harvestable. */
    public static boolean isMatureCrop(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String useOnBlock(ServerPlayer bot, BlockPos pos, ItemStack item) {
        // Select the item in the hotbar if possible, else hold it directly.
        int slot = findHotbarSlot(bot, item);
        if (slot >= 0) {
            bot.getInventory().setSelectedSlot(slot);
        }

        // Determine the face to interact with (top face by default).
        Level world = bot.level();
        BlockState targetState = world.getBlockState(pos);
        Direction face = (targetState.isAir()) ? Direction.UP : Direction.UP;
        BlockPos adjacent = pos;

        LookController.faceBlock(bot, pos);

        Vec3 hitVec = Vec3.atCenterOf(pos);
        BlockHitResult hitResult = new BlockHitResult(hitVec, face, adjacent, false);

        ItemStack handStack = bot.getItemInHand(InteractionHand.MAIN_HAND);
        bot.gameMode.useItemOn(bot, world, handStack, InteractionHand.MAIN_HAND, hitResult);

        return "✅ Used " + item.getItemName().getString() + " at " + pos + ".";
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

    private static int findHotbarSlot(ServerPlayer bot, ItemStack item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item.getItem()) return i;
        }
        return -1;
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
