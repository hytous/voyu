package com.voyu.agent.service.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyu.agent.config.MiddlewareProperties;
import com.voyu.agent.model.infra.ComponentStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class MiddlewareHealthService {

    private final MiddlewareProperties properties;
    private final ObjectMapper objectMapper;

    public MiddlewareHealthService(MiddlewareProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> inspect() {
        Map<String, ComponentStatus> components = new LinkedHashMap<>();
        components.put("mongodb", tcpStatus("mongodb", properties.getMongodb().getHost(), properties.getMongodb().getPort()));
        components.put("kafka", tcpStatus("kafka", properties.getKafka().getHost(), properties.getKafka().getPort()));
        components.put("elasticsearch", elasticsearchStatus());
        components.put("milvus", milvusStatus());
        components.put("minio", httpStatus("minio", properties.getMinio().getUrl()));

        boolean allUp = components.values().stream().allMatch(ComponentStatus::isUp);
        return Map.of(
                "success", allUp,
                "components", components
        );
    }

    private ComponentStatus tcpStatus(String name, String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            return new ComponentStatus(name, true, "TCP reachable", Map.of("host", host, "port", port));
        } catch (IOException ex) {
            return new ComponentStatus(name, false, ex.getMessage(), Map.of("host", host, "port", port));
        }
    }

    private ComponentStatus httpStatus(String name, String url) {
        try {
            HttpResponseData response = executeGet(url);
            boolean up = response.statusCode() >= 200 && response.statusCode() < 300;
            return new ComponentStatus(name, up, "HTTP " + response.statusCode(), Map.of("url", url));
        } catch (Exception ex) {
            return new ComponentStatus(name, false, ex.getMessage(), Map.of("url", url));
        }
    }

    private ComponentStatus elasticsearchStatus() {
        String url = properties.getElasticsearch().getUrl();
        try {
            HttpResponseData response = executeGet(url);
            JsonNode root = objectMapper.readTree(response.body());
            boolean up = response.statusCode() >= 200 && response.statusCode() < 300;
            return new ComponentStatus("elasticsearch", up, "HTTP " + response.statusCode(), Map.of(
                    "url", url,
                    "clusterName", root.path("cluster_name").asText(""),
                    "version", root.path("version").path("number").asText("")
            ));
        } catch (Exception ex) {
            return new ComponentStatus("elasticsearch", false, ex.getMessage(), Map.of("url", url));
        }
    }

    private ComponentStatus milvusStatus() {
        ComponentStatus grpc = tcpStatus("milvus-grpc", properties.getMilvus().getHost(), properties.getMilvus().getGrpcPort());
        ComponentStatus http = httpStatus("milvus-http", properties.getMilvus().getHealthUrl());
        boolean up = grpc.isUp() && http.isUp();
        return new ComponentStatus("milvus", up, up ? "gRPC and health endpoint reachable" : "Milvus not fully ready", Map.of(
                "host", properties.getMilvus().getHost(),
                "grpcPort", properties.getMilvus().getGrpcPort(),
                "healthUrl", properties.getMilvus().getHealthUrl(),
                "grpcUp", grpc.isUp(),
            "httpUp", http.isUp()
        ));
    }

    private HttpResponseData executeGet(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("GET");
        connection.connect();

        int statusCode = connection.getResponseCode();
        InputStream stream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = "";
        if (stream != null) {
            try (InputStream inputStream = stream) {
                body = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        connection.disconnect();
        return new HttpResponseData(statusCode, body);
    }

    private record HttpResponseData(int statusCode, String body) {
    }
}
