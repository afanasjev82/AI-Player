package net.shasankp000.GameAI.autonomous;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows the bot's currently-active goal as a live scoreboard-team suffix, so a
 * watching player sees {@code Paul [building a house]} above the bot's head and
 * in the tab list instead of a bare name.
 *
 * <p>This works from a <em>vanilla</em> client because it uses the standard
 * scoreboard team mechanism — the exact thing {@code /scoreboard teams modify …
 * suffix …} drives — rather than a client mod. {@link ServerPlayer#setCustomName}
 * does NOT work for this: a player's overhead/tab name resolves through the
 * scoreboard team via {@code PlayerList.getPlayerDisplayName}, so the team is the
 * correct hook.
 *
 * <p>Thread-safety: goal labels are set from the autonomous loop's worker
 * threads, but scoreboard mutation must happen on the server thread. All writes
 * are marshalled via {@code server.execute(...)}.
 */
public final class BotStatusLabel {
    private static final Logger LOGGER = LoggerFactory.getLogger("bot-status-label");
    private static final String TEAM_NAME = "aiplayer_status";

    /** Suffix currently applied per bot (game-visible name), or absent. */
    private static final Map<String, String> ACTIVE = new ConcurrentHashMap<>();

    private BotStatusLabel() {}

    /** Whether per-bot status labels are enabled at all. */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("aiplayer.statusLabel", "true"));
    }

    /** Set the visible suffix for a bot (e.g. " [building a house]"). */
    public static void setStatus(String botName, String suffix) {
        if (!isEnabled()) return;
        String trimmed = suffix == null || suffix.isBlank() ? null : suffix.trim();
        String previous = ACTIVE.put(botName, trimmed);
        if (java.util.Objects.equals(previous, trimmed)) return; // no change
        apply(botName);
    }

    /** Clear the status suffix for a bot. */
    public static void clear(String botName) {
        if (ACTIVE.remove(botName) == null) return;
        apply(botName);
    }

    /** Re-apply the current suffix for a bot (used after respawn/rejoin). */
    private static void apply(String botName) {
        MinecraftServer server = net.shasankp000.AIPlayer.serverInstance;
        if (server == null) return;
        if (server.isSameThread()) {
            doApply(server, botName);
        } else {
            server.execute(() -> doApply(server, botName));
        }
    }

    private static void doApply(MinecraftServer server, String botName) {
        try {
            Scoreboard sb = server.getScoreboard();
            PlayerTeam team = sb.getPlayerTeam(TEAM_NAME);
            if (team == null) {
                team = sb.addPlayerTeam(TEAM_NAME);
                team.setDisplayName(Component.literal("AI-Player status"));
            }

            ServerPlayer bot = server.getPlayerList().getPlayerByName(botName);
            String suffix = ACTIVE.get(botName);
            if (suffix != null) {
                team.setPlayerSuffix(Component.literal(" " + suffix));
                if (bot != null) {
                    PlayerTeam current = sb.getPlayersTeam(bot.getScoreboardName());
                    if (!team.equals(current)) {
                        sb.addPlayerToTeam(bot.getScoreboardName(), team);
                    }
                }
            } else {
                // No label: remove the bot from the team (keeps the name clean).
                if (bot != null) {
                    PlayerTeam current = sb.getPlayersTeam(bot.getScoreboardName());
                    if (current != null && TEAM_NAME.equals(current.getName())) {
                        sb.removePlayerFromTeam(bot.getScoreboardName(), current);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to update status label for {}: {}", botName, e.getMessage());
        }
    }
}
