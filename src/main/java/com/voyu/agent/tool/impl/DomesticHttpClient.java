package com.voyu.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
class DomesticHttpClient {

    private final ObjectMapper objectMapper;

    DomesticHttpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode getJson(String url) throws Exception {
        return request("GET", url, null, Map.of());
    }

    JsonNode postJson(String url, Map<String, Object> body, Map<String, String> headers) throws Exception {
        return request("POST", url, objectMapper.writeValueAsString(body), headers);
    }

    String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("");
    }

    private JsonNode request(String method, String url, String body, Map<String, String> headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = "";
        if (stream != null) {
            try (InputStream inputStream = stream) {
                responseBody = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        connection.disconnect();

        if (status < 200 || status >= 300) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("httpStatus", status);
            error.put("body", responseBody);
            return objectMapper.valueToTree(error);
        }
        if (responseBody.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(responseBody);
    }
}
