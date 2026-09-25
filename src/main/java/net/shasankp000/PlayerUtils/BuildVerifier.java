package net.shasankp000.PlayerUtils;

import net.shasankp000.FilingSystem.LLMClientFactory;
import net.shasankp000.ServiceLLMClients.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Experimental LLM-in-the-loop build advisor.
 *
 * <p>When the deterministic build recovery (search site → relocate → shrink)
 * is exhausted, this class optionally asks the configured LLM for one final
 * recommendation on what to try next. It is OFF by default and opt-in via the
 * JVM flag {@code -Daiplayer.llmBuildVerifier=true}, because the default
 * deterministic loop is fast and never blocks on a slow or timing-out model
 * (qwen3:8b has been observed to take ~8s and occasionally time out).
 *
 * <p>This is a follow-up experiment, not the production path. The LLM's advice
 * is a coarse one-word hint (retry / smaller / different-block / give-up) that
 * the caller translates into a concrete action; the LLM never touches the world
 * directly and its response is not trusted beyond that coarse signal.
 */
public final class BuildVerifier {
    private static final Logger LOGGER = LoggerFactory.getLogger("build-verifier");

    /** Coarse next-action signal extracted from a single LLM response. */
    public enum Advice { RETRY, SMALLER, DIFFERENT_BLOCK, GIVE_UP }

    private BuildVerifier() {}

    /** Whether the LLM-in-the-loop experiment is enabled. */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("aiplayer.llmBuildVerifier", "false"));
    }

    /**
     * Ask the configured LLM what to do after a build failed, and reduce its
     * free-form answer to a coarse {@link Advice}. Never throws: any failure
     * falls back to {@link Advice#RETRY}.
     *
     * @param structure     the structure that was attempted
     * @param blockType     the material that was attempted
     * @param failureReason why the build failed (last error message)
     */
    public static Advice consult(String structure, String blockType, String failureReason) {
        LLMClient client;
        try {
            client = LLMClientFactory.createClient(System.getProperty("aiplayer.llmMode", "custom"));
        } catch (Exception e) {
            LOGGER.warn("Could not create LLM client for build verification: {}", e.getMessage());
            return Advice.RETRY;
        }
        if (client == null) {
            LOGGER.warn("LLM client unavailable — build verification skipped");
            return Advice.RETRY;
        }

        String system = "You are a Minecraft construction advisor. Given a failed build "
                + "attempt, choose the single best next action. Reply with exactly one word: "
                + "retry, smaller, different-block, or give-up.";
        String user = "The bot tried to build a '" + structure + "' out of '" + blockType
                + "' but failed: " + failureReason + ". What should it do next?";

        try {
            String response = client.sendPrompt(system, user);
            if (response == null) return Advice.RETRY;
            String lower = response.toLowerCase();
            if (lower.contains("give") || lower.contains("stop") || lower.contains("unable")
                    || lower.contains("cannot") || lower.contains("can't") || lower.contains("no")) {
                return Advice.GIVE_UP;
            }
            if (lower.contains("smaller") || lower.contains("shelter") || lower.contains("wall")
                    || lower.contains("shrink")) {
                return Advice.SMALLER;
            }
            if (lower.contains("different") || lower.contains("stone") || lower.contains("cobble")
                    || lower.contains("material")) {
                return Advice.DIFFERENT_BLOCK;
            }
            return Advice.RETRY;
        } catch (Exception e) {
            LOGGER.warn("Build verification LLM call failed: {}", e.getMessage());
            return Advice.RETRY;
        }
    }
}
