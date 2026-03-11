package com.niconicode.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class EmbeddingService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;

    public EmbeddingService(RestTemplate restTemplate,
                            @Value("${ai.embedding.base-url}") String baseUrl,
                            @Value("${ai.embedding.api-key}") String apiKey,
                            @Value("${ai.embedding.model-name}") String modelName) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    public float[] embed(String text) {
        String url = baseUrl + "/embeddings";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", modelName);
        ArrayNode input = objectMapper.createArrayNode();
        input.add(text);
        body.set("input", input);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body.toString(), headers), String.class);
            JsonNode data = objectMapper.readTree(resp.getBody()).get("data").get(0).get("embedding");
            float[] embedding = new float[data.size()];
            for (int i = 0; i < data.size(); i++) {
                embedding[i] = (float) data.get(i).asDouble();
            }
            return embedding;
        } catch (Exception e) {
            log.error("Embedding failed for text: {}", text.substring(0, Math.min(50, text.length())), e);
            throw new RuntimeException("Embedding 生成失败", e);
        }
    }
}
