package com.voyu.mcpserver;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ExternalTravelTools {

    private final DomesticHttpClient httpClient;
    private final String amapApiKey;
    private final String tencentApiKey;
    private final String bochaApiKey;
    private final String seniversePrivateKey;

    public ExternalTravelTools(DomesticHttpClient httpClient,
                               @Value("${voyu.domestic.amap.api-key:${AMAP_MAPS_API_KEY:}}") String amapApiKey,
                               @Value("${voyu.domestic.tencent-map.api-key:${TENCENT_MAP_API_KEY:}}") String tencentApiKey,
                               @Value("${voyu.domestic.bocha.api-key:${BOCHA_API_KEY:}}") String bochaApiKey,
                               @Value("${voyu.domestic.seniverse.private-key:${SENIVERSE_PRIVATE_KEY:}}") String seniversePrivateKey) {
        this.httpClient = httpClient;
        this.amapApiKey = amapApiKey;
        this.tencentApiKey = tencentApiKey;
        this.bochaApiKey = bochaApiKey;
        this.seniversePrivateKey = seniversePrivateKey;
    }

    @Tool(name = "weather.lookup", description = "Provide weather-oriented planning hints for the destination via Seniverse or AMap.")
    public Map<String, Object> weatherLookup(
            @ToolParam(description = "Destination city or region") String destination,
            @ToolParam(description = "Date range, for example 近期 or 2026-05-01 至 2026-05-03", required = false) String dateRange) {
        String safeDestination = valueOrDefault(destination, "目的地");
        String safeDateRange = valueOrDefault(dateRange, "近期");

        Map<String, Object> seniverse = lookupSeniverse(safeDestination, safeDateRange);
        if (!seniverse.isEmpty()) {
            return seniverse;
        }

        Map<String, Object> amap = lookupAmapWeather(safeDestination, safeDateRange);
        if (!amap.isEmpty()) {
            return amap;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("destination", safeDestination);
        result.put("dateRange", safeDateRange);
        result.put("source", "local-fallback");
        result.put("summary", safeDestination + " 在 " + safeDateRange + " 当前采用占位天气策略：建议保留 1 个室内备选点。");
        result.put("planningHint", "每日最多安排 2 个硬性打卡点，避免天气波动导致全日失效。");
        return result;
    }

    @Tool(name = "map.poi.search", description = "Return representative POIs and grouping suggestions for a destination via AMap or Tencent Map.")
    public Map<String, Object> mapPoiSearch(
            @ToolParam(description = "Destination city or region") String destination,
            @ToolParam(description = "Freeform user query", required = false) String query,
            @ToolParam(description = "POI keywords", required = false) String keywords,
            @ToolParam(description = "POI category, such as 景点、美食、博物馆", required = false) String category) {
        String safeDestination = valueOrDefault(destination, "目的地");
        String safeQuery = nullToEmpty(query);
        String safeKeywords = nullToEmpty(keywords);
        String safeCategory = nullToEmpty(category);
        String searchKeyword = chooseKeyword(safeDestination, safeQuery, safeKeywords, safeCategory);

        Map<String, Object> amap = searchAmapPoi(safeDestination, searchKeyword);
        if (!amap.isEmpty()) {
            return amap;
        }

        Map<String, Object> tencent = searchTencentPoi(safeDestination, searchKeyword);
        if (!tencent.isEmpty()) {
            return tencent;
        }

        return fallbackPoiResult(safeDestination, safeQuery, safeKeywords, safeCategory);
    }

    @Tool(name = "map.route.plan", description = "Estimate route distance and duration between departure and destination via AMap.")
    public Map<String, Object> mapRoutePlan(
            @ToolParam(description = "Departure city or origin address") String departure,
            @ToolParam(description = "Destination city or address") String destination) {
        String origin = nullToEmpty(departure);
        String target = nullToEmpty(destination);
        if (!StringUtils.hasText(amapApiKey)) {
            return Map.of("error", "AMAP_MAPS_API_KEY 未配置");
        }
        if (!StringUtils.hasText(origin) || !StringUtils.hasText(target)) {
            return Map.of("error", "map.route.plan 需要 departure 和 destination");
        }

        try {
            String originPoint = geocode(origin);
            String destinationPoint = geocode(target);
            if (!StringUtils.hasText(originPoint) || !StringUtils.hasText(destinationPoint)) {
                return Map.of("error", "高德地理编码失败", "origin", origin, "destination", target);
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
            result.put("destination", target);
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

    @Tool(name = "web.search", description = "Search current Chinese web information for travel planning via Bocha.")
    public Map<String, Object> webSearch(
            @ToolParam(description = "Search query") String query,
            @ToolParam(description = "Maximum result count, 1 to 10", required = false) Integer count) {
        String safeQuery = nullToEmpty(query);
        if (!StringUtils.hasText(bochaApiKey)) {
            return Map.of("error", "BOCHA_API_KEY 未配置");
        }
        if (!StringUtils.hasText(safeQuery)) {
            return Map.of("error", "web.search 缺少 query");
        }

        try {
            int safeCount = count == null ? 6 : Math.max(1, Math.min(count, 10));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", safeQuery);
            body.put("count", safeCount);
            body.put("summary", true);

            JsonNode root = httpClient.postJson(
                    "https://api.bochaai.com/v1/web-search",
                    body,
                    Map.of("Authorization", "Bearer " + bochaApiKey)
            );
            List<Map<String, Object>> results = extractBochaResults(root);
            if (results.isEmpty()) {
                return Map.of(
                        "error", "Bocha search returned no usable results",
                        "query", safeQuery,
                        "rawStatus", root.path("code").asText(root.path("status").asText(""))
                );
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", safeQuery);
            payload.put("source", "bocha");
            payload.put("results", results);
            payload.put("summary", "博查实时搜索返回 " + results.size() + " 条结果，可用于核对开放状态、近期攻略和活动信息。");
            return payload;
        } catch (Exception ex) {
            return Map.of("error", "Bocha search failed: " + ex.getMessage(), "query", safeQuery);
        }
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

    private Map<String, Object> lookupAmapWeather(String destination, String dateRange) {
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

            List<Map<String, Object>> casts = new ArrayList<>();
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
            result.put("summary", "%s 近期天气：%s/%s，约 %s-%s°C。".formatted(
                    result.get("destination"),
                    first.get("dayWeather"),
                    first.get("nightWeather"),
                    first.get("nightTempC"),
                    first.get("dayTempC")));
            result.put("planningHint", weatherPlanningHint(String.valueOf(first.get("dayWeather"))));
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> searchAmapPoi(String destination, String keyword) {
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

            Map<String, Object> result = basePoiResult(destination, names, details);
            result.put("source", "amap");
            result.put("keyword", keyword);
            result.put("groupingHint", "高德 POI 已返回真实点位，建议按地理邻近和交通时间聚合。");
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> searchTencentPoi(String destination, String keyword) {
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

            Map<String, Object> result = basePoiResult(destination, names, details);
            result.put("source", "tencent-map");
            result.put("keyword", keyword);
            result.put("groupingHint", "腾讯位置服务已返回真实点位，建议按区域和交通耗时聚合。");
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> extractBochaResults(JsonNode root) {
        JsonNode candidates = root.path("data").path("webPages").path("value");
        if (!candidates.isArray()) {
            candidates = root.path("webPages").path("value");
        }
        if (!candidates.isArray()) {
            candidates = root.path("data").path("value");
        }
        if (!candidates.isArray()) {
            candidates = root.path("results");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        if (!candidates.isArray()) {
            return results;
        }
        for (JsonNode item : candidates) {
            String title = firstText(item, "name", "title");
            String url = firstText(item, "url", "displayUrl");
            String snippet = firstText(item, "snippet", "summary", "description");
            if (!StringUtils.hasText(title) && !StringUtils.hasText(url)) {
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("title", title);
            result.put("url", url);
            result.put("snippet", snippet);
            result.put("siteName", firstText(item, "siteName", "site"));
            result.put("date", firstText(item, "dateLastCrawled", "datePublished", "date"));
            results.add(result);
            if (results.size() >= 10) {
                break;
            }
        }
        return results;
    }

    private Map<String, Object> fallbackPoiResult(String destination, String query, String keywords, String category) {
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

    private Map<String, Object> basePoiResult(String destination, List<String> names, List<Map<String, Object>> details) {
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

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String valueOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
