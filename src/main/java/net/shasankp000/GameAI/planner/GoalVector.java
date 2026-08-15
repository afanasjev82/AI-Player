package net.shasankp000.GameAI.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles goal and action embedding for semantic similarity matching.
 * Uses simple TF-IDF-like approach for now (can be upgraded to use embedding models later).
 */
public class GoalVector {
    private static final Logger LOGGER = LoggerFactory.getLogger("goal-vector");

    private static final int EMBEDDING_DIM = 64;

    public GoalVector() {
        LOGGER.info("✓ GoalVector initialized with dimension {}", EMBEDDING_DIM);
    }

    /**
     * Embeds a goal description into a vector.
     * Currently uses simple keyword-based embedding.
     */
    public float[] embedGoal(String goalDescription) {
        float[] embedding = new float[EMBEDDING_DIM];

        // Simple keyword-based embedding
        String normalized = goalDescription.toLowerCase();

        // Mining/gathering keywords
        if (normalized.contains("mine") || normalized.contains("gather") || normalized.contains("collect")) {
            embedding[0] = 1.0f;
            embedding[1] = 0.8f;
        }

        // Wood/tree keywords
        if (normalized.contains("wood") || normalized.contains("log") || normalized.contains("tree")) {
            embedding[2] = 1.0f;
            embedding[3] = 0.9f;
        }

        // Stone keywords
        if (normalized.contains("stone") || normalized.contains("cobblestone") || normalized.contains("rock")) {
            embedding[4] = 1.0f;
            embedding[5] = 0.9f;
        }

        // Navigation keywords
        if (normalized.contains("go") || normalized.contains("move") || normalized.contains("walk")) {
            embedding[6] = 1.0f;
            embedding[7] = 0.7f;
        }

        // Combat keywords
        if (normalized.contains("attack") || normalized.contains("fight") || normalized.contains("kill")) {
            embedding[8] = 1.0f;
            embedding[9] = 0.8f;
        }

        // Building keywords
        if (normalized.contains("build") || normalized.contains("place") || normalized.contains("construct")) {
            embedding[10] = 1.0f;
            embedding[11] = 0.8f;
        }

        // Search keywords
        if (normalized.contains("search") || normalized.contains("find") || normalized.contains("locate")) {
            embedding[12] = 1.0f;
            embedding[13] = 0.7f;
        }

        // Tool/equipment keywords
        if (normalized.contains("axe") || normalized.contains("pickaxe") || normalized.contains("tool")) {
            embedding[14] = 1.0f;
            embedding[15] = 0.6f;
        }

        // Remaining dimensions are left as 0.0. This keeps embeddings
        // deterministic so cosine similarity is meaningful: random noise in the
        // bulk of the vector would drown out the keyword signal and make the
        // "closest action to goal" selection effectively random.

        // Normalize the embedding
        float norm = 0.0f;
        for (float v : embedding) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);

        if (norm > 0) {
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] /= norm;
            }
        }

        return embedding;
    }

    /**
     * Computes cosine similarity between two embeddings.
     */
    public double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Embeddings must have same dimension");
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        normA = Math.sqrt(normA);
        normB = Math.sqrt(normB);

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (normA * normB);
    }

    /**
     * Computes Euclidean distance between two embeddings.
     */
    public double euclideanDistance(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Embeddings must have same dimension");
        }

        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }

        return Math.sqrt(sum);
    }
}

