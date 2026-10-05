package net.shasankp000.ChatUtils.Classifier;

import com.google.gson.JsonObject;

/**
 * Builders for the Laya question schemas AI-Player asks. Shared by the single
 * classifiers ({@link LayaIntentClassifier}, {@link LayaGoalClassifier}) and
 * the combined {@link LayaClient#decide} batch, so the criteria text is defined
 * in exactly one place.
 *
 * <p>{@code choice} criteria keys are the labels Laya returns, so they must map
 * 1:1 onto the caller's vocabulary ({@code NLPProcessor.Intent} enum names and
 * the {@code GoalMapper} goal names respectively).
 */
final class LayaQuestions {

    private LayaQuestions() {
    }

    static JsonObject intent() {
        return choice(
                "Which of these best describes what the player wants the bot to do?",
                new String[][]{
                        {"REQUEST_ACTION",
                                "The player is asking the bot to perform an action in the world: mine, build, craft, move, navigate, fight, gather, farm, or trade."},
                        {"ASK_INFORMATION",
                                "The player is asking a question or requesting information or an explanation."},
                        {"GENERAL_CONVERSATION",
                                "Casual conversation, greetings, or chat with no task and no question."},
                });
    }

    static JsonObject goal() {
        return choice(
                "Which Minecraft goal does this map to?",
                new String[][]{
                        {"mine", "mining, digging, or breaking blocks to extract ores or stone"},
                        {"build", "placing blocks, building structures, houses, walls, or shelters"},
                        {"craft", "crafting, making, or assembling items or tools"},
                        {"navigate", "moving, going, walking, or traveling to a location"},
                        {"combat", "fighting, attacking, killing, or defending against mobs"},
                        {"gather", "collecting, gathering, fetching, or obtaining resources or items"},
                        {"explore", "searching, finding, exploring, or looking for something"},
                        {"farm", "farming, planting, growing, or harvesting crops"},
                        {"trade", "trading, buying, selling, or exchanging with villagers"},
                });
    }

    static JsonObject urgency() {
        JsonObject question = new JsonObject();
        question.addProperty("type", "score");
        question.addProperty("instructions", "How urgent is this request?");
        com.google.gson.JsonArray levels = new com.google.gson.JsonArray();
        levels.add("casual");
        levels.add("needs doing soon");
        levels.add("urgent");
        question.add("criteria", levels);
        return question;
    }

    private static JsonObject choice(String instructions, String[][] criteria) {
        JsonObject question = new JsonObject();
        question.addProperty("type", "choice");
        question.addProperty("instructions", instructions);
        JsonObject options = new JsonObject();
        for (String[] pair : criteria) {
            options.addProperty(pair[0], pair[1]);
        }
        question.add("criteria", options);
        return question;
    }
}
