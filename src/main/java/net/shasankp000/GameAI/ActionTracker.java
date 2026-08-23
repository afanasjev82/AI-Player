package net.shasankp000.GameAI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks in-flight bot actions to prevent action spam and enforce completion.
 *
 * <p>Extracted from {@link BotEventHandler} (Phase: god-object split, step 3).
 * That class previously held three static maps
 * ({@code actionInProgress}/{@code currentAction}/{@code actionStartTime}) plus
 * the four methods {@code isActionInProgress}/{@code startAction}/
 * {@code completeAction}/{@code waitForActionCompletion} directly. This class
 * owns that state and logic; {@link BotEventHandler} now delegates to a single
 * static {@code ActionTracker} instance through thin static wrappers, so the
 * public API (e.g. {@code BotEventHandler.completeAction(...)}) is unchanged.
 *
 * <p>Semantics are intentionally preserved byte-for-byte from the original:
 * the maps are plain {@link HashMap} (the original used {@code HashMap}; a
 * future hardening pass could switch to {@code ConcurrentHashMap} since these
 * are touched from the AutoFaceEntity executor, the RL loop, and the command
 * thread).
 */
public final class ActionTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("action-tracker");

    private static final long ACTION_TIMEOUT_MS = 5000; // 5 second timeout per action

    private final Map<String, Boolean> inProgress = new HashMap<>();
    private final Map<String, String> currentAction = new HashMap<>();
    private final Map<String, Long> startTime = new HashMap<>();

    /** True if the given bot has an action in-flight (auto-completes on timeout). */
    public boolean isInProgress(String botName) {
        Boolean inProgress = this.inProgress.get(botName);
        if (inProgress == null || !inProgress) return false;

        Long startedAt = startTime.get(botName);
        if (startedAt != null && System.currentTimeMillis() - startedAt > ACTION_TIMEOUT_MS) {
            LOGGER.warn("[ACTION] Action '{}' timed out after {}ms - forcing completion",
                currentAction.get(botName), ACTION_TIMEOUT_MS);
            complete(botName);
            return false;
        }
        return true;
    }

    /** Mark the given action as started for the bot. */
    public void start(String botName, String actionName) {
        inProgress.put(botName, true);
        currentAction.put(botName, actionName);
        startTime.put(botName, System.currentTimeMillis());
        LOGGER.debug("[ACTION] Started: {}", actionName);
    }

    /** Mark the bot's current action as complete. */
    public void complete(String botName) {
        inProgress.put(botName, false);
        String completedAction = currentAction.get(botName);
        currentAction.remove(botName);
        startTime.remove(botName);
        if (completedAction != null) {
            LOGGER.debug("[ACTION] Completed: {}", completedAction);
        }
    }

    /** Block until the bot's current action completes, or {@code timeoutMs} elapses. */
    public void waitForCompletion(String botName, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (isInProgress(botName)) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                LOGGER.warn("[ACTION] waitForActionCompletion timed out after {}ms", timeoutMs);
                complete(botName);
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** The name of the bot's current in-flight action, or {@code null}. */
    public String getCurrentAction(String botName) {
        return currentAction.get(botName);
    }
}
