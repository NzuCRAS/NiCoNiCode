package com.niconicode.agent.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niconicode.agent.chat.dto.IntentClassification;
import com.niconicode.agent.chat.dto.IntentClassification.ClassifiedBy;
import com.niconicode.agent.chat.dto.IntentClassification.Intent;
import com.niconicode.agent.chat.dto.IntentClassification.SubIntent;
import com.niconicode.knowledge.service.EmbeddingService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

/**
 * L2 向量相似度分类器 — 启动时加载样本向量到内存，运行时余弦相似度匹配
 */
@Slf4j
@Component
public class VectorIntentClassifier {

    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<IntentExemplar> exemplars = new ArrayList<>();
    private volatile boolean ready = false;

    public VectorIntentClassifier(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @PostConstruct
    public void init() {
        Thread loader = new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                InputStream is = new ClassPathResource("intent-exemplars.json").getInputStream();
                List<ExemplarJson> jsonList = objectMapper.readValue(is, new TypeReference<>() {});
                log.info("Loaded {} intent exemplars from JSON", jsonList.size());

                // 批量 embed（逐条调用，EmbeddingService 目前只支持单条）
                int success = 0;
                for (ExemplarJson ej : jsonList) {
                    try {
                        float[] vector = embeddingService.embed(ej.text);
                        Intent intent = Intent.valueOf(ej.intent);
                        SubIntent subIntent;
                        try {
                            subIntent = SubIntent.valueOf(ej.subIntent);
                        } catch (Exception e) {
                            subIntent = SubIntent.UNKNOWN;
                        }
                        exemplars.add(new IntentExemplar(ej.text, vector, intent, subIntent, ej.slots));
                        success++;
                    } catch (Exception e) {
                        log.warn("Failed to embed exemplar: {}", ej.text, e);
                    }
                }

                ready = true;
                long duration = System.currentTimeMillis() - start;
                log.info("VectorIntentClassifier ready: {}/{} exemplars loaded in {}ms",
                        success, jsonList.size(), duration);
            } catch (Exception e) {
                log.error("Failed to initialize VectorIntentClassifier", e);
            }
        }, "vector-intent-init");
        loader.setDaemon(true);
        loader.start();
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * 向量相似度分类，返回 null 表示不可用或无法分类
     */
    public IntentClassification classify(String message) {
        if (!ready || exemplars.isEmpty() || message == null || message.isBlank()) {
            return null;
        }

        try {
            float[] queryVector = embeddingService.embed(message);

            // 计算余弦相似度并取 topK=5
            List<ScoredExemplar> scored = new ArrayList<>(exemplars.size());
            for (IntentExemplar ex : exemplars) {
                double sim = cosineSimilarity(queryVector, ex.vector);
                scored.add(new ScoredExemplar(ex, sim));
            }
            scored.sort((a, b) -> Double.compare(b.score, a.score));

            List<ScoredExemplar> topK = scored.subList(0, Math.min(5, scored.size()));

            // 按 (intent, subIntent) 分组取加权均值
            Map<String, AggregatedScore> groups = new LinkedHashMap<>();
            for (ScoredExemplar se : topK) {
                String key = se.exemplar.intent.name() + "/" + se.exemplar.subIntent.name();
                groups.computeIfAbsent(key, k -> new AggregatedScore(se.exemplar.intent, se.exemplar.subIntent))
                        .add(se.score, se.exemplar.slots);
            }

            // 找最高分组
            AggregatedScore best = null;
            AggregatedScore second = null;
            for (AggregatedScore agg : groups.values()) {
                if (best == null || agg.weightedAvg() > best.weightedAvg()) {
                    second = best;
                    best = agg;
                } else if (second == null || agg.weightedAvg() > second.weightedAvg()) {
                    second = agg;
                }
            }

            if (best == null) return null;

            double confidence = best.weightedAvg();

            // 合并 slots（取最高分样本的 slots）
            Map<String, String> slots = best.bestSlots != null ? new HashMap<>(best.bestSlots) : new HashMap<>();

            IntentClassification.IntentClassificationBuilder builder = IntentClassification.builder()
                    .primaryIntent(best.intent)
                    .subIntent(best.subIntent)
                    .confidence(confidence)
                    .slots(slots)
                    .classifiedBy(ClassifiedBy.VECTOR);

            // 备选意图
            if (second != null && second.weightedAvg() > 0.5) {
                builder.alternativeIntents(List.of(
                        IntentClassification.AlternativeIntent.builder()
                                .intent(second.intent)
                                .subIntent(second.subIntent)
                                .confidence(second.weightedAvg())
                                .build()
                ));
            }

            return builder.build();
        } catch (Exception e) {
            log.warn("Vector classification failed: {}", e.getMessage());
            return null;
        }
    }

    // ---- 余弦相似度 ----

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    // ---- 内部数据结构 ----

    private static class IntentExemplar {
        final String text;
        final float[] vector;
        final Intent intent;
        final SubIntent subIntent;
        final Map<String, String> slots;

        IntentExemplar(String text, float[] vector, Intent intent, SubIntent subIntent, Map<String, String> slots) {
            this.text = text;
            this.vector = vector;
            this.intent = intent;
            this.subIntent = subIntent;
            this.slots = slots;
        }
    }

    private static class ScoredExemplar {
        final IntentExemplar exemplar;
        final double score;

        ScoredExemplar(IntentExemplar exemplar, double score) {
            this.exemplar = exemplar;
            this.score = score;
        }
    }

    private static class AggregatedScore {
        final Intent intent;
        final SubIntent subIntent;
        double totalScore = 0;
        int count = 0;
        Map<String, String> bestSlots;
        double bestScore = 0;

        AggregatedScore(Intent intent, SubIntent subIntent) {
            this.intent = intent;
            this.subIntent = subIntent;
        }

        void add(double score, Map<String, String> slots) {
            totalScore += score;
            count++;
            if (score > bestScore) {
                bestScore = score;
                bestSlots = slots;
            }
        }

        double weightedAvg() {
            return count == 0 ? 0 : totalScore / count;
        }
    }

    // JSON 反序列化用
    private static class ExemplarJson {
        public String text;
        public String intent;
        public String subIntent;
        public Map<String, String> slots;
    }
}
