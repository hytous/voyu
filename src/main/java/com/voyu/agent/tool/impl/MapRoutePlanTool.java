package com.voyu.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.voyu.agent.tool.ToolCapabilityType;
import com.voyu.agent.tool.ToolTemplate;
import com.voyu.agent.tool.TravelTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MapRoutePlanTool implements TravelTool {

    private static final ToolTemplate TEMPLATE = ToolTemplate.local(
            "map.route.plan",
            "路线估算",
            "Estimate route distance and duration between departure and destination via AMap.",
            ToolCapabilityType.ROUTE_PLAN,
            true,
            true,
            ToolTemplate.jsonObjectSchema("departure", "origin", "destination"));

    private final DomesticHttpClient httpClient;
    private final String amapApiKey;

    public MapRoutePlanTool(DomesticHttpClient httpClient,
                            @Value("${voyu.domestic.amap.api-key:${AMAP_MAPS_API_KEY:}}") String amapApiKey) {
        this.httpClient = httpClient;
        this.amapApiKey = amapApiKey;
    }

    @Override
    public ToolTemplate template() {
        return TEMPLATE;
    }

    @Override
    public String name() {
        return "map.route.plan";
    }

    @Override
    public String description() {
        return "Estimate route distance and duration between departure and destination via AMap.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        String origin = String.valueOf(input.getOrDefault("departure", input.getOrDefault("origin", "")));
        String destination = String.valueOf(input.getOrDefault("destination", ""));
        if (!StringUtils.hasText(amapApiKey)) {
            return Map.of("error", "AMAP_MAPS_API_KEY 未配置");
        }
        if (!StringUtils.hasText(origin) || !StringUtils.hasText(destination)) {
            return Map.of("error", "map.route.plan 需要 departure/origin 和 destination");
        }

        try {
            String originPoint = geocode(origin);
            String destinationPoint = geocode(destination);
            if (!StringUtils.hasText(originPoint) || !StringUtils.hasText(destinationPoint)) {
                return Map.of("error", "高德地理编码失败", "origin", origin, "destination", destination);
            }

            String url = "https://restapi.amap.com/v3/direction/driving"
                    + "?key=" + httpClient.encode(amapApiKey)
                    + "&origin=" + httpClient.encode(originPoint)
                    + "&destination=" + httpClient.encode(destinationPoint)
                    + "&strategy=10";
            JsonNode root = httpClient.getJson(url);
            if (!"1".equals(root.path("status").asText())) {
                return Map.of("error", "高德路线规划失败", "info", root.path("info").asText(""));
            }
            JsonNode path = root.path("route").path("paths").isArray() && !root.path("route").path("paths").isEmpty()
                    ? root.path("route").path("paths").get(0)
                    : null;
            if (path == null) {
                return Map.of("error", "高德路线规划未返回路线");
            }

            long distanceMeters = path.path("distance").asLong(0);
            long durationSeconds = path.path("duration").asLong(0);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", "amap");
            result.put("origin", origin);
            result.put("destination", destination);
            result.put("distanceKm", Math.round(distanceMeters / 100.0) / 10.0);
            result.put("durationHours", Math.round(durationSeconds / 360.0) / 10.0);
            result.put("tolls", path.path("tolls").asText(""));
            result.put("summary", "高德驾车路线估算：约 %s 公里，约 %s 小时。".formatted(
                    result.get("distanceKm"),
                    result.get("durationHours")));
            return result;
        } catch (Exception ex) {
            return Map.of("error", "高德路线规划异常: " + ex.getMessage());
        }
    }

    private String geocode(String address) throws Exception {
        String url = "https://restapi.amap.com/v3/geocode/geo"
                + "?key=" + httpClient.encode(amapApiKey)
                + "&address=" + httpClient.encode(address);
        JsonNode root = httpClient.getJson(url);
        if (!"1".equals(root.path("status").asText()) || !root.path("geocodes").isArray() || root.path("geocodes").isEmpty()) {
            return "";
        }
        return root.path("geocodes").get(0).path("location").asText("");
    }
}
