package com.voyu.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.voyu.agent.tool.ToolCapabilityType;
import com.voyu.agent.tool.ToolTemplate;
import com.voyu.agent.tool.TravelTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class MapPoiSearchTool implements TravelTool {

    private static final ToolTemplate TEMPLATE = ToolTemplate.local(
            "map.poi.search",
            "POI 检索",
            "Return representative POIs and grouping suggestions for a city.",
            ToolCapabilityType.POI_SEARCH,
            true,
            true,
            ToolTemplate.jsonObjectSchema("destination", "query", "keywords", "category"));

    private final DomesticHttpClient httpClient;
    private final String amapApiKey;
    private final String tencentApiKey;

    public MapPoiSearchTool(DomesticHttpClient httpClient,
                            @Value("${voyu.domestic.amap.api-key:${AMAP_MAPS_API_KEY:}}") String amapApiKey,
                            @Value("${voyu.domestic.tencent-map.api-key:${TENCENT_MAP_API_KEY:}}") String tencentApiKey) {
        this.httpClient = httpClient;
        this.amapApiKey = amapApiKey;
        this.tencentApiKey = tencentApiKey;
    }

    @Override
    public ToolTemplate template() {
        return TEMPLATE;
    }

    @Override
    public String name() {
        return "map.poi.search";
    }

    @Override
    public String description() {
        return "Return representative POIs and grouping suggestions for a city.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        String destination = String.valueOf(input.getOrDefault("destination", "目的地"));
        String query = String.valueOf(input.getOrDefault("query", ""));
        String keywords = String.valueOf(input.getOrDefault("keywords", ""));
        String category = String.valueOf(input.getOrDefault("category", ""));
        String searchKeyword = chooseKeyword(destination, query, keywords, category);

        Map<String, Object> apiResult = searchAmap(destination, searchKeyword);
        if (!apiResult.isEmpty()) {
            return apiResult;
        }

        apiResult = searchTencent(destination, searchKeyword);
        if (!apiResult.isEmpty()) {
            return apiResult;
        }

        return fallbackResult(destination, query, keywords, category);
    }

    private Map<String, Object> searchAmap(String destination, String keyword) {
        if (!StringUtils.hasText(amapApiKey) || !StringUtils.hasText(keyword)) {
            return Map.of();
        }
        try {
            String url = "https://restapi.amap.com/v3/place/text"
                    + "?key=" + httpClient.encode(amapApiKey)
                    + "&keywords=" + httpClient.encode(keyword)
                    + "&city=" + httpClient.encode(destination)
                    + "&citylimit=false&offset=10&page=1&extensions=base";
            JsonNode root = httpClient.getJson(url);
            if (!"1".equals(root.path("status").asText()) || !root.path("pois").isArray()) {
                return Map.of();
            }

            List<String> names = new ArrayList<>();
            List<Map<String, Object>> details = new ArrayList<>();
            for (JsonNode poi : root.path("pois")) {
                String name = poi.path("name").asText("");
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                names.add(name);
                details.add(poiDetail(
                        name,
                        poi.path("type").asText(""),
                        poi.path("address").isArray() ? "" : poi.path("address").asText(""),
                        poi.path("location").asText(""),
                        poi.path("pname").asText(""),
                        poi.path("cityname").asText("")
                ));
            }
            if (names.isEmpty()) {
                return Map.of();
            }

            Map<String, Object> result = baseResult(destination, names, details);
            result.put("source", "amap");
            result.put("keyword", keyword);
            result.put("groupingHint", "高德 POI 已返回真实点位，建议按地理邻近和交通时间聚合，避免跨区反复折返。");
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> searchTencent(String destination, String keyword) {
        if (!StringUtils.hasText(tencentApiKey) || !StringUtils.hasText(keyword)) {
            return Map.of();
        }
        try {
            String boundary = StringUtils.hasText(destination) ? "region(" + destination + ",0)" : "region(中国,0)";
            String url = "https://apis.map.qq.com/ws/place/v1/search"
                    + "?key=" + httpClient.encode(tencentApiKey)
                    + "&keyword=" + httpClient.encode(keyword)
                    + "&boundary=" + httpClient.encode(boundary)
                    + "&page_size=10";
            JsonNode root = httpClient.getJson(url);
            if (root.path("status").asInt(-1) != 0 || !root.path("data").isArray()) {
                return Map.of();
            }

            List<String> names = new ArrayList<>();
            List<Map<String, Object>> details = new ArrayList<>();
            for (JsonNode poi : root.path("data")) {
                String name = poi.path("title").asText("");
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                names.add(name);
                JsonNode location = poi.path("location");
                String coordinate = location.isMissingNode()
                        ? ""
                        : location.path("lng").asText("") + "," + location.path("lat").asText("");
                details.add(poiDetail(
                        name,
                        poi.path("category").asText(""),
                        poi.path("address").asText(""),
                        coordinate,
                        poi.path("ad_info").path("province").asText(""),
                        poi.path("ad_info").path("city").asText("")
                ));
            }
            if (names.isEmpty()) {
                return Map.of();
            }

            Map<String, Object> result = baseResult(destination, names, details);
            result.put("source", "tencent-map");
            result.put("keyword", keyword);
            result.put("groupingHint", "腾讯位置服务已返回真实点位，建议按区域和交通耗时聚合。");
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> fallbackResult(String destination, String query, String keywords, String category) {
        String lower = destination.toLowerCase(Locale.ROOT);
        String categoryContext = (keywords + " " + category).toLowerCase(Locale.ROOT);
        String fullContext = (query + " " + keywords + " " + category).toLowerCase(Locale.ROOT);

        List<String> pois;
        if ((lower.contains("tokyo") || destination.contains("东京")) && isSightseeingQuery(categoryContext)) {
            pois = List.of("浅草寺", "上野公园", "明治神宫", "涩谷十字路口", "东京晴空塔");
        } else if ((lower.contains("tokyo") || destination.contains("东京")) && isFoodQuery(fullContext)) {
            pois = List.of("筑地场外市场", "浅草美食街", "新宿思い出横丁", "涩谷横丁", "阿美横町");
        } else if ((lower.contains("tokyo") || destination.contains("东京"))) {
            pois = List.of("浅草寺", "上野公园", "涩谷十字路口", "明治神宫", "东京晴空塔");
        } else if ((lower.contains("osaka") || destination.contains("大阪")) && isSightseeingQuery(categoryContext)) {
            pois = List.of("心斋桥", "大阪城", "通天阁", "中崎町", "法善寺横丁");
        } else if ((lower.contains("osaka") || destination.contains("大阪")) && isFoodQuery(fullContext)) {
            pois = List.of("道顿堀", "黑门市场", "新世界", "法善寺横丁", "梅田阪神地下街");
        } else if (lower.contains("osaka") || destination.contains("大阪")) {
            pois = List.of("道顿堀", "心斋桥", "大阪城", "梅田蓝天大厦", "新世界");
        } else if ((lower.contains("kyoto") || destination.contains("京都")) && isSightseeingQuery(categoryContext)) {
            pois = List.of("清水寺", "祇园", "伏见稻荷大社", "岚山", "金阁寺");
        } else if ((lower.contains("kyoto") || destination.contains("京都")) && isFoodQuery(fullContext)) {
            pois = List.of("锦市场", "先斗町", "祇园小路", "鸭川纳凉床", "京都站拉面小路");
        } else if (lower.contains("kyoto") || destination.contains("京都")) {
            pois = List.of("清水寺", "祇园", "伏见稻荷大社", "岚山", "金阁寺");
        } else {
            pois = List.of("城市核心地标", "历史文化点", "美食聚集区", "夜景点", "室内备选点");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("destination", destination);
        result.put("pois", pois);
        result.put("source", "local-fallback");
        result.put("groupingHint", "建议按区域聚合景点，减少跨城或跨区跳转。");
        return result;
    }

    private String chooseKeyword(String destination, String query, String keywords, String category) {
        if (StringUtils.hasText(keywords)) {
            return keywords;
        }
        if (StringUtils.hasText(category)) {
            return destination + " " + category;
        }
        if (StringUtils.hasText(query)) {
            return query;
        }
        return destination + " 景点 美食";
    }

    private Map<String, Object> baseResult(String destination, List<String> names, List<Map<String, Object>> details) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("destination", destination);
        result.put("pois", names);
        result.put("poiDetails", details);
        return result;
    }

    private Map<String, Object> poiDetail(String name,
                                          String type,
                                          String address,
                                          String location,
                                          String province,
                                          String city) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", name);
        detail.put("type", type);
        detail.put("address", address);
        detail.put("location", location);
        detail.put("province", province);
        detail.put("city", city);
        return detail;
    }

    private boolean isFoodQuery(String context) {
        return context.contains("food")
                || context.contains("美食")
                || context.contains("拉面")
                || context.contains("章鱼烧")
                || context.contains("大阪烧")
                || context.contains("市场");
    }

    private boolean isSightseeingQuery(String context) {
        return context.contains("观光")
                || context.contains("景点")
                || context.contains("步行")
                || context.contains("漫步")
                || context.contains("街区")
                || context.contains("历史")
                || context.contains("城")
                || context.contains("寺");
    }
}
