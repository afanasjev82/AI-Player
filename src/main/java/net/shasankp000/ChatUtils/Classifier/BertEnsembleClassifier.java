package net.shasankp000.ChatUtils.Classifier;

import ai.djl.modality.Classifications;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.fabricmc.loader.api.FabricLoader;
import net.shasankp000.AIPlayer;
import net.shasankp000.ChatUtils.CART.CartClassifier;
import net.shasankp000.ChatUtils.DecisionResolver.DecisionResolver;
import net.shasankp000.ChatUtils.LIDSNetModel.LIDSNetModelManager;
import net.shasankp000.ChatUtils.NLPProcessor;
import net.shasankp000.ChatUtils.PreProcessing.OpenNLPProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * The built-in intent classifier: the BERT + CART + LIDSNet ensemble behind the
 * {@link IntentClassifier} seam.
 *
 * <p>Runs the three local classifiers, composes their labels and confidences
 * through the LLM-backed {@link DecisionResolver}, then (if the resolver fails)
 * falls back to the highest-confidence local label above a 0.60 threshold. The
 * returned {@link ClassificationResult} label is an
 * {@link NLPProcessor.Intent} enum name so {@code NLPProcessor.getIntention} can
 * map it straight back to the enum.
 *
 * <p>Every sub-model is wrapped in its own try/catch, so one broken model
 * degrades to the others rather than failing the whole request.
 */
public class BertEnsembleClassifier implements IntentClassifier {

    private static final Logger LOGGER = LoggerFactory.getLogger("BertEnsembleClassifier");

    /** Minimum normalized local confidence to accept a fallback label. */
    private static final double FALLBACK_CONFIDENCE_THRESHOLD = 0.60;

    @Override
    public ClassificationResult classify(String playerMessage) {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path modelDir = configDir.resolve("ai-player/NLPModels");
        Path cartDir = modelDir.resolve("cart_files");
        Path vocabFilePath = cartDir.resolve("cart_vectorizer_vocab.json");
        Path labelsFilePath = cartDir.resolve("cart_class_labels.json");
        Path treeFilePath = cartDir.resolve("cart_tree.json");
        Path openNlpModelsDir = modelDir.resolve("OpenNLPModels");
        Path lidsNetModelDir = modelDir.resolve("LIDSNet_torchscript/");

        double bertConfidence = 0;
        double cartConfidence = 0;
        double lidsNetConfidence = 0;

        CartClassifier cartClassifier = null;
        try {
            cartClassifier = new CartClassifier(
                    treeFilePath.toFile(), labelsFilePath.toFile(), vocabFilePath.toFile());
        } catch (IOException e) {
            LOGGER.error("Error initializing CART classifier! {}", e.getMessage());
        }

        String bertLabel = null;
        String cartLabel = null;
        String lidsNetLabel = null;

        // ── BERT ─────────────────────────────────────────────────────────────
        try {
            Classifications intent = AIPlayer.modelManager.predict(playerMessage);
            if (intent != null) {
                bertLabel = intent.best().getClassName();
                bertConfidence = intent.best().getProbability();
                LOGGER.info("BERT predicted: {} with confidence: {}", bertLabel, bertConfidence);
            }
        } catch (Exception e) {
            LOGGER.error("Error predicting intent using BERT: {}", e.getMessage());
        }

        // ── CART ─────────────────────────────────────────────────────────────
        try {
            if (cartClassifier != null) {
                CartClassifier.ClassificationResult result = cartClassifier.classify(playerMessage);
                cartLabel = result.label;
                cartConfidence = result.confidence;
                LOGGER.info("CART predicted: {} with confidence: {}", cartLabel, cartConfidence);
            } else {
                throw new Exception("CART classifier is null!");
            }
        } catch (Exception e) {
            LOGGER.error("Error predicting intent using CART: {}", e.getMessage());
        }

        // ── LIDSNet ──────────────────────────────────────────────────────────
        try {
            ObjectMapper mapper = new ObjectMapper();
            Path actualLidsNetModelDir = lidsNetModelDir.resolve("LIDSNet_torchscript/");
            JsonNode root = mapper.readTree(
                    new File(actualLidsNetModelDir.resolve("lidsnet_feature_map.json").toString()));

            TreeMap<Integer, String> classIdxMap = new TreeMap<>();
            root.get("idx2label").fields().forEachRemaining(entry ->
                    classIdxMap.put(Integer.parseInt(entry.getKey()), entry.getValue().asText()));
            List<String> classNames = new ArrayList<>(classIdxMap.values());

            List<String> featureNames = new ArrayList<>();
            root.get("features").forEach(f -> featureNames.add(f.asText()));

            OpenNLPProcessor openNLP = new OpenNLPProcessor(openNlpModelsDir.toString());
            List<OpenNLPProcessor.TokenInfo> tokens = openNLP.analyze(playerMessage);

            Set<String> presentFeatures = new HashSet<>();
            for (OpenNLPProcessor.TokenInfo token : tokens) {
                presentFeatures.add("POS=" + token.posTag);
                presentFeatures.add("lemma=" + token.lemma);
            }

            float[] inputVector = new float[featureNames.size()];
            for (int i = 0; i < featureNames.size(); i++) {
                inputVector[i] = presentFeatures.contains(featureNames.get(i)) ? 1.0f : 0.0f;
            }

            LIDSNetModelManager lidsNet = LIDSNetModelManager.getInstance(actualLidsNetModelDir);
            lidsNet.loadModel(classNames);
            LIDSNetModelManager.PredictionResult pred = lidsNet.predictWithConfidence(inputVector, classNames);

            System.out.printf("[LIDSNet Classifier] Sentence: \"%s\"\nPredicted intent: %s (Confidence: %.2f%%)\n",
                    playerMessage, pred.getClassName(), pred.getConfidencePercentage());

            lidsNetLabel = pred.getClassName();
            lidsNetConfidence = pred.getConfidencePercentage();
        } catch (Exception e) {
            LOGGER.error("Error while running inference: {}", e.getMessage());
        }

        // ── DecisionResolver (LLM composition) ───────────────────────────────
        String decision = null;
        try {
            decision = new DecisionResolver().resolveIntent(
                    playerMessage,
                    bertLabel, bertConfidence,
                    cartLabel, cartConfidence,
                    lidsNetLabel, lidsNetConfidence);
        } catch (Exception e) {
            LOGGER.error("Error while resolving the final decision: {}", e.getMessage());
        }

        ClassificationResult resolved = parseResolverDecision(decision);
        if (resolved != null) {
            return resolved;
        }

        ClassificationResult localFallback = chooseLocalClassifierFallback(
                bertLabel, bertConfidence,
                cartLabel, cartConfidence,
                lidsNetLabel, lidsNetConfidence);
        if (localFallback != null) {
            LOGGER.warn("Intent resolver did not return a usable decision. Using local classifier fallback: {}",
                    localFallback.label());
            return localFallback;
        }

        return null;
    }

    /** Map the LLM resolver's text onto a result, or {@code null} when unusable. */
    private static ClassificationResult parseResolverDecision(String decision) {
        if (decision == null || decision.isBlank()) {
            LOGGER.warn("Intent resolver returned an empty decision. Falling back to UNSPECIFIED.");
            return null;
        }
        String normalized = decision.trim();
        if (isSupportedIntentLabel(normalized)) {
            return new ClassificationResult(normalized, 1.0, null);
        }
        LOGGER.warn("Intent resolver returned unsupported decision '{}'. Falling back to UNSPECIFIED.", normalized);
        return null;
    }

    /** Highest-confidence supported local label, or {@code null} below threshold. */
    private static ClassificationResult chooseLocalClassifierFallback(
            String bertLabel, double bertConfidence,
            String cartLabel, double cartConfidence,
            String lidsNetLabel, double lidsNetConfidence) {
        String bestLabel = null;
        double bestConfidence = 0.0;

        double normalizedBert = normalizeConfidence(bertConfidence);
        if (normalizedBert > bestConfidence && isSupportedIntentLabel(bertLabel)) {
            bestLabel = bertLabel.trim();
            bestConfidence = normalizedBert;
        }

        double normalizedCart = normalizeConfidence(cartConfidence);
        if (normalizedCart > bestConfidence && isSupportedIntentLabel(cartLabel)) {
            bestLabel = cartLabel.trim();
            bestConfidence = normalizedCart;
        }

        double normalizedLids = normalizeConfidence(lidsNetConfidence);
        if (normalizedLids > bestConfidence && isSupportedIntentLabel(lidsNetLabel)) {
            bestLabel = lidsNetLabel.trim();
            bestConfidence = normalizedLids;
        }

        return bestConfidence >= FALLBACK_CONFIDENCE_THRESHOLD
                ? new ClassificationResult(bestLabel, bestConfidence, null)
                : null;
    }

    private static double normalizeConfidence(double confidence) {
        return confidence > 1.0 ? confidence / 100.0 : confidence;
    }

    private static boolean isSupportedIntentLabel(String label) {
        if (label == null || label.isBlank()) {
            return false;
        }
        try {
            NLPProcessor.Intent.valueOf(label.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
