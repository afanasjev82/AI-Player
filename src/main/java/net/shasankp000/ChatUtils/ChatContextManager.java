package net.shasankp000.ChatUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChatContextManager {
    private static final Map<UUID, ClarificationState> pendingClarifications = new HashMap<>();

    public static void setPendingClarification(UUID playerUUID, String originalMessage, String clarifyingQuestion, String botName) {
        // A console-sent message has no player (null UUID); there is no one to
        // clarify with, so don't record a pending clarification under a null key.
        if (playerUUID == null) {
            return;
        }
        pendingClarifications.put(playerUUID, new ClarificationState(originalMessage, clarifyingQuestion, botName));
    }

    public static ClarificationState getPendingClarification(UUID playerUUID) {
        return pendingClarifications.get(playerUUID);
    }

    public static void clearPendingClarification(UUID playerUUID) {
        pendingClarifications.remove(playerUUID);
    }

    public static boolean isAwaitingClarification(UUID playerUUID) {
        return playerUUID != null && pendingClarifications.containsKey(playerUUID);
    }
}

