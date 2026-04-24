package com.voyu.agent.service.knowledge;

import com.voyu.agent.model.knowledge.KnowledgeChunk;
import com.voyu.agent.model.knowledge.KnowledgeHit;
import com.voyu.agent.model.knowledge.KnowledgeSearchResult;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KnowledgeBaseService {

    private static final int MAX_CHUNK_LENGTH = 2800;

    @Value("classpath:knowledge/travel-knowledge.md")
    private Resource knowledgeResource;

    @Value("${voyu.rag.top-k:4}")
    private int topK;

    @Value("${voyu.rag.candidate-limit:10}")
    private int candidateLimit;

    private final ElasticsearchKnowledgeStore elasticsearchStore;
    private final MilvusKnowledgeStore milvusStore;
    private final SiliconFlowRerankerService rerankerService;

    private final Map<String, List<String>> destinationAliases = new LinkedHashMap<>(defaultDestinationAliases());
    private final List<KnowledgeChunk> chunks = new ArrayList<>();

    public KnowledgeBaseService(ElasticsearchKnowledgeStore elasticsearchStore,
                                MilvusKnowledgeStore milvusStore,
                                SiliconFlowRerankerService rerankerService) {
        this.elasticsearchStore = elasticsearchStore;
        this.milvusStore = milvusStore;
        this.rerankerService = rerankerService;
    }

    @PostConstruct
    public void init() throws IOException {
        int index = 0;
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:knowledge/**/*.md");
        if (resources.length == 0) {
            resources = new Resource[]{knowledgeResource};
        }

        for (Resource resource : resources) {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            registerDestinationAliases(content);
            for (String block : splitBlocks(content)) {
                String normalized = block.trim();
                if (!normalized.isBlank()) {
                    chunks.add(toChunk(normalized, index++));
                }
            }
        }
        elasticsearchStore.sync(chunks);
        milvusStore.sync(chunks);
    }

    public List<String> search(String query) {
        return searchKnowledge(query).getHits().stream()
                .map(hit -> hit.getChunk().getTitle() + ": " + hit.getChunk().getContent())
                .toList();
    }

    public KnowledgeSearchResult searchKnowledge(String query) {
        String rewrittenQuery = rewriteQuery(query);
        List<String> destinations = extractDestinations(query);
        int routeCandidateLimit = Math.max(candidateLimit, topK);

        List<KnowledgeHit> esHits = elasticsearchStore.search(rewrittenQuery, destinations, routeCandidateLimit);
        List<KnowledgeHit> milvusHits = milvusStore.search(rewrittenQuery, routeCandidateLimit);
        List<KnowledgeHit> localHits = esHits.isEmpty() && milvusHits.isEmpty()
                ? localSearch(rewrittenQuery, destinations, routeCandidateLimit)
                : List.of();

        return new KnowledgeSearchResult(
                query,
                rewrittenQuery,
                destinations,
                fuse(rewrittenQuery, destinations, esHits, milvusHits, localHits)
        );
    }

    private List<KnowledgeHit> fuse(String rerankQuery,
                                    List<String> destinations,
                                    List<KnowledgeHit> esHits,
                                    List<KnowledgeHit> milvusHits,
                                    List<KnowledgeHit> localHits) {
        Map<String, FusedHit> fused = new LinkedHashMap<>();
        mergeRoute(fused, esHits, "elasticsearch", destinations);
        mergeRoute(fused, milvusHits, "milvus", destinations);
        mergeRoute(fused, localHits, "local", destinations);

        List<FusedHit> ranked = fused.values().stream()
                .sorted(Comparator.comparingDouble(FusedHit::score).reversed())
                .toList();
        List<KnowledgeHit> candidates = selectRerankCandidates(ranked, destinations).stream()
                .map(item -> new KnowledgeHit(item.chunk(), item.score(), item.sources()))
                .toList();
        List<KnowledgeHit> reranked = rerankerService.rerank(rerankQuery, candidates, topK);
        return ensureDestinationCoverage(reranked, candidates, destinations);
    }

    private void mergeRoute(Map<String, FusedHit> fused,
                            List<KnowledgeHit> hits,
                            String route,
                            List<String> destinations) {
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeHit hit = hits.get(i);
            double score = 1.0 / (60 + i + 1);
            score += destinationBoost(hit.getChunk(), destinations);
            FusedHit current = fused.get(hit.getChunk().getId());
            if (current == null) {
                fused.put(hit.getChunk().getId(), new FusedHit(hit.getChunk(), score, route));
                continue;
            }
            fused.put(hit.getChunk().getId(), new FusedHit(
                    current.chunk(),
                    current.score() + score,
                    mergeSources(current.sources(), route)
            ));
        }
    }

    private String mergeSources(String current, String next) {
        LinkedHashSet<String> sources = new LinkedHashSet<>(List.of(current.split("\\+")));
        sources.add(next);
        return String.join("+", sources);
    }

    private List<FusedHit> selectRerankCandidates(List<FusedHit> ranked, List<String> destinations) {
        int maxCandidates = Math.max(topK * 2, topK + 2);
        LinkedHashMap<String, FusedHit> selected = new LinkedHashMap<>();
        for (String destination : destinations) {
            ranked.stream()
                    .filter(hit -> destination.equals(hit.chunk().getDestination()))
                    .limit(2)
                    .forEach(hit -> selected.putIfAbsent(hit.chunk().getId(), hit));
        }
        for (FusedHit hit : ranked) {
            selected.putIfAbsent(hit.chunk().getId(), hit);
            if (selected.size() >= maxCandidates) {
                break;
            }
        }
        return new ArrayList<>(selected.values());
    }

    private List<KnowledgeHit> ensureDestinationCoverage(List<KnowledgeHit> reranked,
                                                         List<KnowledgeHit> candidates,
                                                         List<String> destinations) {
        if (destinations.size() <= 1 || reranked.isEmpty()) {
            return reranked.stream().limit(topK).toList();
        }
        LinkedHashMap<String, KnowledgeHit> selected = new LinkedHashMap<>();
        reranked.forEach(hit -> selected.putIfAbsent(hit.getChunk().getId(), hit));
        for (String destination : destinations) {
            boolean present = selected.values().stream()
                    .anyMatch(hit -> destination.equals(hit.getChunk().getDestination()));
            if (present) {
                continue;
            }
            candidates.stream()
                    .filter(hit -> destination.equals(hit.getChunk().getDestination()))
                    .findFirst()
                    .ifPresent(hit -> {
                        if (selected.size() >= topK && !selected.containsKey(hit.getChunk().getId())) {
                            removeTailForCoverage(selected, destinations);
                        }
                        selected.putIfAbsent(hit.getChunk().getId(), hit);
                    });
        }
        return selected.values().stream()
                .limit(topK)
                .toList();
    }

    private void removeTailForCoverage(LinkedHashMap<String, KnowledgeHit> selected, List<String> destinations) {
        Map<String, Long> counts = selected.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        hit -> hit.getChunk().getDestination(),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()
                ));
        List<String> ids = new ArrayList<>(selected.keySet());
        for (int i = ids.size() - 1; i >= 0; i--) {
            KnowledgeHit hit = selected.get(ids.get(i));
            String destination = hit.getChunk().getDestination();
            boolean replaceable = !destinations.contains(destination) || counts.getOrDefault(destination, 0L) > 1;
            if (replaceable) {
                selected.remove(ids.get(i));
                return;
            }
        }
        if (!ids.isEmpty()) {
            selected.remove(ids.get(ids.size() - 1));
        }
    }

    private List<KnowledgeHit> localSearch(String query, List<String> destinations, int limit) {
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return chunks.stream()
                .map(chunk -> new LocalRank(chunk, score(normalizedQuery, chunk.searchableText().toLowerCase(Locale.ROOT), destinations)))
                .filter(item -> item.score > 0)
                .sorted(Comparator.comparingInt(LocalRank::score).reversed())
                .limit(limit)
                .map(item -> new KnowledgeHit(item.chunk(), item.score(), "local"))
                .toList();
    }

    private int score(String query, String chunk, List<String> destinations) {
        int score = 0;
        for (String token : query.split("[\\s,，。；;:/]+")) {
            if (!token.isBlank() && chunk.contains(token)) {
                score += 3;
            }
        }
        for (String destination : destinations) {
            if (!destination.isBlank() && chunk.contains(destination.toLowerCase(Locale.ROOT))) {
                score += 6;
            }
            for (String alias : destinationAliases.getOrDefault(destination, List.of())) {
                if (!alias.isBlank() && chunk.contains(alias.toLowerCase(Locale.ROOT))) {
                    score += 4;
                }
            }
        }
        if (chunk.contains("travel heuristics")) {
            score += 1;
        }
        return score;
    }

    private double destinationBoost(KnowledgeChunk chunk, List<String> destinations) {
        if (chunk == null || destinations == null || destinations.isEmpty()) {
            return 0.0;
        }
        String destination = chunk.getDestination() == null ? "" : chunk.getDestination();
        if (destination.isBlank()) {
            return 0.0;
        }
        return destinations.contains(destination) ? 0.05 : 0.0;
    }

    private KnowledgeChunk toChunk(String block, int index) {
        int lineBreak = block.indexOf('\n');
        String title = lineBreak >= 0 ? block.substring(0, lineBreak).trim() : block.trim();
        if (title.startsWith("# ")) {
            title = title.substring(2).trim();
        }
        String body = lineBreak >= 0 ? block.substring(lineBreak + 1).trim() : block.trim();
        String destination = normalizeDestination(title);

        List<String> keywords = new ArrayList<>();
        if (!destination.isBlank()) {
            keywords.add(destination);
        }
        if (!title.isBlank()) {
            keywords.add(title.toLowerCase(Locale.ROOT));
        }
        for (String token : body.split("[\\s,，。；;:/()]+")) {
            if (!token.isBlank() && token.length() > 1 && keywords.size() < 8) {
                keywords.add(token.toLowerCase(Locale.ROOT));
            }
        }

        return new KnowledgeChunk(
                slug(title, index),
                title,
                destination,
                body,
                keywords.stream().distinct().toList()
        );
    }

    private List<String> splitBlocks(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        List<MarkdownSection> documents = splitByHeading(normalized, "(?m)^#\\s+(.+?)\\s*$");
        if (documents.isEmpty()) {
            return chunkBody("Travel knowledge", normalized);
        }

        List<String> blocks = new ArrayList<>();
        for (MarkdownSection document : documents) {
            blocks.addAll(splitDocument(document.title(), document.body()));
        }
        return blocks;
    }

    private List<String> splitDocument(String documentTitle, String documentBody) {
        String body = documentBody == null ? "" : documentBody.trim();
        if (body.isBlank()) {
            return List.of("# " + documentTitle);
        }

        List<MarkdownSection> sections = splitByHeading(body, "(?m)^##\\s+(.+?)\\s*$");
        if (sections.isEmpty()) {
            return chunkBody(documentTitle, body);
        }

        List<String> blocks = new ArrayList<>();
        for (MarkdownSection section : sections) {
            String title = documentTitle;
            if (!section.title().isBlank()) {
                title = documentTitle.isBlank() ? section.title() : documentTitle + " - " + section.title();
            }
            blocks.addAll(chunkBody(title, section.body()));
        }
        return blocks;
    }

    private List<MarkdownSection> splitByHeading(String content, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(content);
        List<MarkdownSection> sections = new ArrayList<>();
        int previousEnd = 0;
        String previousTitle = "";

        while (matcher.find()) {
            if (!previousTitle.isBlank()) {
                String body = content.substring(previousEnd, matcher.start()).trim();
                if (!body.isBlank()) {
                    sections.add(new MarkdownSection(previousTitle, body));
                }
            } else if (matcher.start() > 0) {
                String lead = content.substring(0, matcher.start()).trim();
                if (!lead.isBlank()) {
                    sections.add(new MarkdownSection("", lead));
                }
            }
            previousTitle = matcher.group(1).trim();
            previousEnd = matcher.end();
        }

        if (!previousTitle.isBlank()) {
            String tail = content.substring(previousEnd).trim();
            if (!tail.isBlank()) {
                sections.add(new MarkdownSection(previousTitle, tail));
            }
        }
        return sections;
    }

    private List<String> chunkBody(String title, String body) {
        List<String> blocks = new ArrayList<>();
        List<String> paragraphs = List.of(body.split("\\R{2,}"));
        StringBuilder builder = new StringBuilder();

        for (String rawParagraph : paragraphs) {
            String paragraph = rawParagraph.trim();
            if (paragraph.isBlank()) {
                continue;
            }

            if (paragraph.length() > MAX_CHUNK_LENGTH) {
                flushChunk(blocks, title, builder);
                for (String part : splitLongParagraph(paragraph)) {
                    blocks.add(formatBlock(title, part));
                }
                continue;
            }

            int projectedLength = builder.isEmpty()
                    ? paragraph.length()
                    : builder.length() + 2 + paragraph.length();
            if (projectedLength > MAX_CHUNK_LENGTH) {
                flushChunk(blocks, title, builder);
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(paragraph);
        }

        flushChunk(blocks, title, builder);
        return blocks;
    }

    private void flushChunk(List<String> blocks, String title, StringBuilder builder) {
        if (builder.isEmpty()) {
            return;
        }
        blocks.add(formatBlock(title, builder.toString()));
        builder.setLength(0);
    }

    private List<String> splitLongParagraph(String paragraph) {
        List<String> parts = new ArrayList<>();
        String remaining = paragraph;
        while (remaining.length() > MAX_CHUNK_LENGTH) {
            int cut = remaining.lastIndexOf(". ", MAX_CHUNK_LENGTH);
            if (cut < MAX_CHUNK_LENGTH / 2) {
                cut = remaining.lastIndexOf(' ', MAX_CHUNK_LENGTH);
            }
            if (cut < MAX_CHUNK_LENGTH / 2) {
                cut = MAX_CHUNK_LENGTH;
            }
            parts.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isBlank()) {
            parts.add(remaining);
        }
        return parts;
    }

    private String formatBlock(String title, String body) {
        String normalizedTitle = (title == null || title.isBlank()) ? "Travel knowledge" : title.trim();
        return "# " + normalizedTitle + "\n" + body.trim();
    }

    private String rewriteQuery(String query) {
        String normalized = query == null ? "" : query.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return normalized;
        }
        List<String> destinations = extractDestinations(normalized);
        if (destinations.isEmpty()) {
            return normalized;
        }
        StringJoiner joiner = new StringJoiner(" ");
        joiner.add(normalized);
        for (String destination : destinations) {
            joiner.add(destination);
            for (String alias : destinationAliases.getOrDefault(destination, List.of())) {
                if (!alias.isBlank()) {
                    joiner.add(alias);
                }
            }
        }
        joiner.add("travel itinerary");
        return joiner.toString();
    }

    public List<String> recognizeDestinations(String query) {
        return extractDestinations(query);
    }

    public int chunkCount() {
        return chunks.size();
    }

    private List<String> extractDestinations(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> destinations = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : destinationAliases.entrySet()) {
            if (containsDestination(normalized, entry.getKey(), entry.getValue())) {
                destinations.add(entry.getKey());
            }
        }
        return destinations;
    }

    private String extractDestination(String query) {
        return extractDestinations(query).stream().findFirst().orElse("");
    }

    private String normalizeDestination(String title) {
        String normalized = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : destinationAliases.entrySet()) {
            if (containsDestination(normalized, entry.getKey(), entry.getValue())) {
                return entry.getKey();
            }
        }
        return "";
    }

    private boolean containsDestination(String normalizedText, String destination, List<String> aliases) {
        if (normalizedText.contains(destination.toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (String alias : aliases) {
            if (normalizedText.contains(alias.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void registerDestinationAliases(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        Matcher matcher = Pattern.compile("(?m)^Chinese destination aliases:\\s*(.+?)\\s*$").matcher(content);
        if (!matcher.find()) {
            return;
        }

        List<String> values = Pattern.compile("\\s*,\\s*")
                .splitAsStream(matcher.group(1).trim())
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (values.isEmpty()) {
            return;
        }

        String destination = values.get(0);
        LinkedHashSet<String> aliases = new LinkedHashSet<>(destinationAliases.getOrDefault(destination, List.of()));
        aliases.addAll(values.subList(1, values.size()));
        String title = documentTitle(content);
        if (!title.isBlank()) {
            aliases.add(title);
        }
        addChineseShortAliases(destination, aliases);
        aliases.remove(destination);
        destinationAliases.put(destination, new ArrayList<>(aliases));
    }

    private void addChineseShortAliases(String destination, LinkedHashSet<String> aliases) {
        if (destination == null || destination.length() <= 2) {
            return;
        }
        if (destination.endsWith("岛") || destination.endsWith("市") || destination.endsWith("省")) {
            aliases.add(destination.substring(0, destination.length() - 1));
        }
    }

    private String documentTitle(String content) {
        Matcher matcher = Pattern.compile("(?m)^#\\s+(.+?)\\s*$").matcher(content);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static Map<String, List<String>> defaultDestinationAliases() {
        LinkedHashMap<String, List<String>> aliases = new LinkedHashMap<>();
        addDestination(aliases, "九寨沟", "Jiuzhaigou", "Jiuzhaigou Nature Reserve", "Jiuzhaigou Valley");
        addDestination(aliases, "张家界", "Zhangjiajie");
        addDestination(aliases, "峨眉山", "Mount Emei", "Emei Shan");
        addDestination(aliases, "黄山", "Huangshan", "Yellow Mountain");
        addDestination(aliases, "泰山", "Mount Tai", "Tai Shan");
        addDestination(aliases, "阳朔", "Yangshuo");
        addDestination(aliases, "大理", "Dali");
        addDestination(aliases, "丽江", "Lijiang");
        addDestination(aliases, "喀什", "Kashgar", "Kashi");
        addDestination(aliases, "敦煌", "Dunhuang");
        addDestination(aliases, "三亚", "Sanya");
        addDestination(aliases, "海口", "Haikou");
        addDestination(aliases, "北京", "Beijing", "Peking");
        addDestination(aliases, "上海", "Shanghai");
        addDestination(aliases, "广州", "Guangzhou", "Canton");
        addDestination(aliases, "深圳", "Shenzhen");
        addDestination(aliases, "成都", "Chengdu");
        addDestination(aliases, "重庆", "Chongqing");
        addDestination(aliases, "西安", "Xi'an", "Xian");
        addDestination(aliases, "杭州", "Hangzhou");
        addDestination(aliases, "苏州", "Suzhou");
        addDestination(aliases, "南京", "Nanjing");
        addDestination(aliases, "武汉", "Wuhan");
        addDestination(aliases, "长沙", "Changsha");
        addDestination(aliases, "厦门", "Xiamen");
        addDestination(aliases, "青岛", "Qingdao", "Tsingtao");
        addDestination(aliases, "天津", "Tianjin");
        addDestination(aliases, "哈尔滨", "Harbin");
        addDestination(aliases, "沈阳", "Shenyang");
        addDestination(aliases, "大连", "Dalian");
        addDestination(aliases, "昆明", "Kunming");
        addDestination(aliases, "桂林", "Guilin");
        addDestination(aliases, "拉萨", "Lhasa");
        addDestination(aliases, "乌鲁木齐", "Urumqi", "Urumchi");
        addDestination(aliases, "兰州", "Lanzhou");
        addDestination(aliases, "西宁", "Xining");
        addDestination(aliases, "香港", "Hong Kong");
        addDestination(aliases, "澳门", "Macau", "Macao");
        addDestination(aliases, "云南", "Yunnan");
        addDestination(aliases, "四川", "Sichuan");
        addDestination(aliases, "广东", "Guangdong");
        addDestination(aliases, "广西", "Guangxi");
        addDestination(aliases, "福建", "Fujian");
        addDestination(aliases, "浙江", "Zhejiang");
        addDestination(aliases, "江苏", "Jiangsu");
        addDestination(aliases, "湖南", "Hunan");
        addDestination(aliases, "湖北", "Hubei");
        addDestination(aliases, "陕西", "Shaanxi", "Shensi");
        addDestination(aliases, "山东", "Shandong");
        addDestination(aliases, "山西", "Shanxi");
        addDestination(aliases, "河南", "Henan");
        addDestination(aliases, "河北", "Hebei");
        addDestination(aliases, "海南", "Hainan");
        addDestination(aliases, "内蒙古", "Inner Mongolia");
        addDestination(aliases, "新疆", "Xinjiang");
        addDestination(aliases, "西藏", "Tibet");
        addDestination(aliases, "贵州", "Guizhou");
        addDestination(aliases, "江西", "Jiangxi");
        addDestination(aliases, "安徽", "Anhui");
        addDestination(aliases, "中国", "China", "Mainland China");
        addDestination(aliases, "东京", "Tokyo");
        addDestination(aliases, "大阪", "Osaka");
        addDestination(aliases, "京都", "Kyoto");
        return Collections.unmodifiableMap(aliases);
    }

    private static void addDestination(Map<String, List<String>> aliases, String destination, String... values) {
        aliases.put(destination, List.of(values));
    }

    private String slug(String title, int index) {
        String candidate = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return candidate.isBlank() ? "chunk-" + index : candidate + "-" + index;
    }

    private record LocalRank(KnowledgeChunk chunk, int score) {
    }

    private record FusedHit(KnowledgeChunk chunk, double score, String sources) {
    }

    private record MarkdownSection(String title, String body) {
    }
}
