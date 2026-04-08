package com.niconicode.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
public class VectorService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String qdrantUrl;
    private final String collectionName;

    /**
     * Qdrant 可用性标记：
     * - true：允许向量 upsert/search/delete
     * - false：进行降级（仅记录 warn，返回空结果），避免外部依赖不可用导致应用无法启动
     */
    private volatile boolean qdrantAvailable = true;

    @Value("${qdrant.vector-dimension:1024}")
    private int vectorDimension;

    public VectorService(RestTemplate restTemplate,
                         @Value("${qdrant.url}") String qdrantUrl,
                         @Value("${qdrant.collection-name}") String collectionName) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
        this.qdrantUrl = qdrantUrl;
        this.collectionName = collectionName;
    }

    @PostConstruct
    public void init() {
        try {
            ensureCollection(vectorDimension);
            qdrantAvailable = true;
        } catch (Exception e) {
            // P0: 降级熔断——Qdrant 不可用不应阻断整个应用启动（Tracker/报表等仍可用）
            qdrantAvailable = false;
            log.warn("Qdrant is not available at startup ({}). Vector search will be disabled until it recovers.", qdrantUrl);
            log.debug("Qdrant init failure", e);
        }
    }

    public boolean isQdrantAvailable() {
        return qdrantAvailable;
    }

    public void ensureCollection(int dimension) {
        try {
            String url = qdrantUrl + "/collections/" + collectionName;
            restTemplate.getForEntity(url, String.class);
            log.info("Qdrant collection '{}' already exists", collectionName);
    } catch (RuntimeException e) {
            createCollection(dimension);
        }
    }

    private void createCollection(int dimension) {
        String url = qdrantUrl + "/collections/" + collectionName;
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode vectors = objectMapper.createObjectNode();
        vectors.put("size", dimension);
        vectors.put("distance", "Cosine");
        body.set("vectors", vectors);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
    restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body.toString(), headers), String.class);
    log.info("Created Qdrant collection '{}' with dimension {}", collectionName, dimension);
    }

    public void upsertVectors(List<float[]> vectors, List<Map<String, Object>> payloads) {
        if (!qdrantAvailable) {
            log.debug("Qdrant disabled, skip upsertVectors (collection={})", collectionName);
            return;
        }
        String url = qdrantUrl + "/collections/" + collectionName + "/points";
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode points = objectMapper.createArrayNode();

        for (int i = 0; i < vectors.size(); i++) {
            ObjectNode point = objectMapper.createObjectNode();
            point.put("id", UUID.randomUUID().toString());
            ArrayNode vec = objectMapper.createArrayNode();
            for (float v : vectors.get(i)) vec.add(v);
            point.set("vector", vec);
            point.set("payload", objectMapper.valueToTree(payloads.get(i)));
            points.add(point);
        }
        body.set("points", points);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
    try {
            restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body.toString(), headers), String.class);
            qdrantAvailable = true;
    } catch (RuntimeException e) {
            qdrantAvailable = false;
            log.warn("Qdrant upsert failed, disabling vector operations temporarily: {}", e.getMessage());
            log.debug("Qdrant upsert failed", e);
        }
    }

    public List<Map<String, Object>> search(float[] queryVector, int topK) {
        if (!qdrantAvailable) {
            return Collections.emptyList();
        }
        String url = qdrantUrl + "/collections/" + collectionName + "/points/search";
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode vec = objectMapper.createArrayNode();
        for (float v : queryVector) vec.add(v);
        body.set("vector", vec);
        body.put("limit", topK);
        body.put("with_payload", true);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
    try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body.toString(), headers), String.class);
            JsonNode result = objectMapper.readTree(resp.getBody()).get("result");
            List<Map<String, Object>> results = new ArrayList<>();
            if (result != null && result.isArray()) {
                for (JsonNode item : result) {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("score", item.get("score").asDouble());
                    JsonNode payload = item.get("payload");
                    if (payload != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> payloadMap = objectMapper.convertValue(payload, Map.class);
                        entry.putAll(payloadMap);
                    }
                    results.add(entry);
                }
            }
            qdrantAvailable = true;
            return results;
    } catch (Exception e) {
            qdrantAvailable = false;
            log.warn("Qdrant search failed, disabling vector operations temporarily: {}", e.getMessage());
            log.debug("Qdrant search failed", e);
            return Collections.emptyList();
        }
    }

    public void deleteBySourceId(Long sourceId) {
        if (!qdrantAvailable) {
            log.debug("Qdrant disabled, skip deleteBySourceId (sourceId={})", sourceId);
            return;
        }
        String url = qdrantUrl + "/collections/" + collectionName + "/points/delete";
    // 避免 formatted，统一使用安全拼接
    String body = "{\"filter\":{\"must\":[{\"key\":\"source_id\",\"match\":{\"value\":"
        + sourceId + "}}]}}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            qdrantAvailable = true;
    } catch (RuntimeException e) {
            qdrantAvailable = false;
            log.warn("Failed to delete vectors for source_id {}, disabling vector operations temporarily: {}", sourceId, e.getMessage());
            log.debug("Failed to delete vectors for source_id {}", sourceId, e);
        }
    }
}
