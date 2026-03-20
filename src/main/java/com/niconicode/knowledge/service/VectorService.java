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
        ensureCollection(vectorDimension);
    }

    public void ensureCollection(int dimension) {
        try {
            String url = qdrantUrl + "/collections/" + collectionName;
            restTemplate.getForEntity(url, String.class);
            log.info("Qdrant collection '{}' already exists", collectionName);
        } catch (Exception e) {
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
        restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body.toString(), headers), String.class);
    }

    public List<Map<String, Object>> search(float[] queryVector, int topK) {
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
                        entry.putAll(objectMapper.convertValue(payload, Map.class));
                    }
                    results.add(entry);
                }
            }
            return results;
        } catch (Exception e) {
            log.error("Qdrant search failed", e);
            return Collections.emptyList();
        }
    }

    public void deleteBySourceId(Long sourceId) {
        String url = qdrantUrl + "/collections/" + collectionName + "/points/delete";
    // 避免 formatted，统一使用安全拼接
    String body = "{\"filter\":{\"must\":[{\"key\":\"source_id\",\"match\":{\"value\":"
        + sourceId + "}}]}}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            log.warn("Failed to delete vectors for source_id {}", sourceId, e);
        }
    }
}
