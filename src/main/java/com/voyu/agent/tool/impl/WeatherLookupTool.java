package com.voyu.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.voyu.agent.tool.ToolCapabilityType;
import com.voyu.agent.tool.ToolTemplate;
import com.voyu.agent.tool.TravelTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WeatherLookupTool implements TravelTool {

    private static final ToolTemplate TEMPLATE = ToolTemplate.local(
            "weather.lookup",
            "天气查询",
            "Provide weather-oriented planning hints for the destination.",
            ToolCapabilityType.WEATHER_LOOKUP,
            true,
            true,
            ToolTemplate.jsonObjectSchema("destination", "dateRange"));

    private final DomesticHttpClient httpClient;
    private final String seniversePrivateKey;
    private final String amapApiKey;

    public WeatherLookupTool(DomesticHttpClient httpClient,
                             @Value("${voyu.domestic.seniverse.private-key:${SENIVERSE_PRIVATE_KEY:}}") String seniversePrivateKey,
                             @Value("${voyu.domestic.amap.api-key:${AMAP_MAPS_API_KEY:}}") String amapApiKey) {
        this.httpClient = httpClient;
        this.seniversePrivateKey = seniversePrivateKey;
        this.amapApiKey = amapApiKey;
    }

    @Override
    public ToolTemplate template() {
        return TEMPLATE;
    }

    @Override
    public String name() {
        return "weather.lookup";
    }

    @Override
    public String description() {
        return "Provide weather-oriented planning hints for the destination.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        String destination = String.valueOf(input.getOrDefault("destination", "目的地"));
        String dateRange = String.valueOf(input.getOrDefault("dateRange", "近期"));

        Map<String, Object> seniverse = lookupSeniverse(destination, dateRange);
        if (!seniverse.isEmpty()) {
            return seniverse;
        }

        Map<String, Object> amap = lookupAmap(destination, dateRange);
        if (!amap.isEmpty()) {
            return amap;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("destination", destination);
        result.put("dateRange", dateRange);
        result.put("source", "local-fallback");
        result.put("summary", destination + " 在 " + dateRange + " 当前采用占位天气策略：建议保留 1 个室内备选点，雨天优先博物馆、商场或观景台。");
        result.put("planningHint", "每日最多安排 2 个硬性打卡点，避免天气波动导致全日失效。");
        return result;
    }

    private Map<String, Object> lookupSeniverse(String destination, String dateRange) {
        if (!StringUtils.hasText(seniversePrivateKey) || !StringUtils.hasText(destination)) {
            return Map.of();
        }
        try {
            String nowUrl = "https://api.seniverse.com/v3/weather/now.json"
                    + "?key=" + httpClient.encode(seniversePrivateKey)
                    + "&location=" + httpClient.encode(destination)
                    + "&language=zh-Hans&unit=c";
            JsonNode now = httpClient.getJson(nowUrl);
            JsonNode nowResult = now.path("results").isArray() && !now.path("results").isEmpty()
                    ? now.path("results").get(0)
                    : null;
            if (nowResult == null) {
                return Map.of();
            }

            String weatherText = nowResult.path("now").path("text").asText("");
            String temperature = nowResult.path("now").path("temperature").asText("");
            String locationName = nowResult.path("location").path("name").asText(destination);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("destination", locationName);
            result.put("dateRange", dateRange);
            result.put("source", "seniverse");
            result.put("current", Map.of(
                    "weather", weatherText,
                    "temperatureC", temperature,
                    "lastUpdate", nowResult.path("last_update").asText("")
            ));
            result.put("summary", "%s 当前天气：%s，%s°C。".formatted(locationName, weatherText, temperature));
            result.put("planningHint", weatherPlanningHint(weatherText));
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> lookupAmap(String destination, String dateRange) {
        if (!StringUtils.hasText(amapApiKey) || !StringUtils.hasText(destination)) {
            return Map.of();
        }
        try {
            String adcode = resolveAmapAdcode(destination);
            if (!StringUtils.hasText(adcode)) {
                return Map.of();
            }
            String url = "https://restapi.amap.com/v3/weather/weatherInfo"
                    + "?key=" + httpClient.encode(amapApiKey)
                    + "&city=" + httpClient.encode(adcode)
                    + "&extensions=all";
            JsonNode root = httpClient.getJson(url);
            if (!"1".equals(root.path("status").asText())) {
                return Map.of();
            }

            JsonNode forecast = root.path("forecasts").isArray() && !root.path("forecasts").isEmpty()
                    ? root.path("forecasts").get(0)
                    : null;
            if (forecast == null) {
                return Map.of();
            }

            List<Map<String, Object>> casts = new java.util.ArrayList<>();
            for (JsonNode cast : forecast.path("casts")) {
                casts.add(Map.of(
                        "date", cast.path("date").asText(""),
                        "dayWeather", cast.path("dayweather").asText(""),
                        "nightWeather", cast.path("nightweather").asText(""),
                        "dayTempC", cast.path("daytemp").asText(""),
                        "nightTempC", cast.path("nighttemp").asText("")
                ));
            }
            if (casts.isEmpty()) {
                return Map.of();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("destination", forecast.path("city").asText(destination));
            result.put("dateRange", dateRange);
            result.put("source", "amap");
            result.put("forecast", casts);
            Map<String, Object> first = casts.get(0);
            String summary = "%s 近期天气：%s/%s，约 %s-%s°C。".formatted(
                    result.get("destination"),
                    first.get("dayWeather"),
                    first.get("nightWeather"),
                    first.get("nightTempC"),
                    first.get("dayTempC"));
            result.put("summary", summary);
            result.put("planningHint", weatherPlanningHint(String.valueOf(first.get("dayWeather"))));
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String resolveAmapAdcode(String destination) throws Exception {
        String url = "https://restapi.amap.com/v3/geocode/geo"
                + "?key=" + httpClient.encode(amapApiKey)
                + "&address=" + httpClient.encode(destination);
        JsonNode root = httpClient.getJson(url);
        if (!"1".equals(root.path("status").asText()) || !root.path("geocodes").isArray() || root.path("geocodes").isEmpty()) {
            return "";
        }
        return root.path("geocodes").get(0).path("adcode").asText("");
    }

    private String weatherPlanningHint(String weatherText) {
        String normalized = weatherText == null ? "" : weatherText;
        if (normalized.contains("雨") || normalized.contains("雪") || normalized.contains("雷")) {
            return "安排室内备选点，户外景点放在天气较稳定的半天，并减少跨区通勤。";
        }
        if (normalized.contains("晴") || normalized.contains("多云")) {
            return "适合安排户外景点，但仍建议保留一个室内备选点并控制日均步行强度。";
        }
        return "天气条件不明朗，建议保留室内备选点并避免把核心体验全部押在户外。";
    }
}
