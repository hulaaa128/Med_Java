package com.echomind.knowledge;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final String MODE_HYBRID = "hybrid_rrf";
    private static final String MODE_BM25_ONLY = "bm25_only";
    private static final String MODE_VECTOR_ONLY = "vector_only";

    private final EchoMindProperties properties;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectMapper objectMapper;
    private final List<KnowledgeDocument> documents = new CopyOnWriteArrayList<>();
    private final DocumentSplitter splitter = DocumentSplitters.recursive(500, 80);
    private final AtomicBoolean vectorHealthy = new AtomicBoolean(false);
    private final AtomicReference<String> lastVectorError = new AtomicReference<>("");
    private final AtomicReference<String> lastRetrievalMode = new AtomicReference<>("not_used");

    public KnowledgeBaseService(EchoMindProperties properties,
                                ObjectProvider<VectorStore> vectorStoreProvider,
                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.vectorStoreProvider = vectorStoreProvider;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadPersistedDocuments();
        if (documents.isEmpty()) {
            addDocuments(defaultDocuments());
            return;
        }
        syncVectorStore(List.copyOf(documents));
    }

    public synchronized int addDocuments(List<Map<String, String>> inputDocs) {
        List<KnowledgeDocument> newChunks = new ArrayList<>();
        List<String> replacedIds = new ArrayList<>();

        for (Map<String, String> input : inputDocs) {
            String title = input.getOrDefault("title", "未命名文档");
            String content = input.getOrDefault("content", "");
            String source = input.getOrDefault("source", "echomind-java");
            String version = input.getOrDefault("version", "v1");
            String documentKey = md5(source + "|" + title);

            documents.stream()
                    .filter(existing -> documentKey.equals(String.valueOf(existing.metadata().get("document_key"))))
                    .map(KnowledgeDocument::id)
                    .forEach(replacedIds::add);
            documents.removeIf(existing -> documentKey.equals(String.valueOf(existing.metadata().get("document_key"))));

            List<TextSegment> segments = split(content);
            for (int i = 0; i < segments.size(); i++) {
                String text = segments.get(i).text();
                if (text == null || text.isBlank()) {
                    continue;
                }
                String id = md5(documentKey + "|" + i);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("title", title);
                metadata.put("chunk_index", i);
                metadata.put("source", source);
                metadata.put("version", version);
                metadata.put("document_key", documentKey);
                newChunks.add(new KnowledgeDocument(id, title, text, i, Map.copyOf(metadata)));
            }
        }

        if (newChunks.isEmpty()) {
            return 0;
        }

        documents.addAll(newChunks);
        persistDocuments();
        replaceInVectorStore(replacedIds, newChunks);
        return newChunks.size();
    }

    public List<SearchResult> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int recallK = Math.max(topK, topK * Math.max(1, properties.getRag().getRecallMultiplier()));
        List<SearchResult> bm25Results = bm25Search(query, recallK);
        VectorSearchOutcome vectorOutcome = vectorSearch(query, recallK);

        if (!vectorOutcome.available()) {
            lastRetrievalMode.set(MODE_BM25_ONLY);
            return decorateSingleChannel(bm25Results, MODE_BM25_ONLY, "bm25", topK);
        }
        if (bm25Results.isEmpty()) {
            lastRetrievalMode.set(MODE_VECTOR_ONLY);
            return decorateSingleChannel(vectorOutcome.results(), MODE_VECTOR_ONLY, "vector", topK);
        }
        if (vectorOutcome.results().isEmpty()) {
            lastRetrievalMode.set(MODE_BM25_ONLY);
            return decorateSingleChannel(bm25Results, MODE_BM25_ONLY, "bm25", topK);
        }

        lastRetrievalMode.set(MODE_HYBRID);
        return rrfFuse(bm25Results, vectorOutcome.results(), topK);
    }

    public int docCount() {
        return documents.size();
    }

    public Map<String, Object> retrievalStatus() {
        return Map.of(
                "vector_configured", properties.getRag().isVectorEnabled(),
                "vector_healthy", vectorHealthy.get(),
                "last_retrieval_mode", lastRetrievalMode.get(),
                "last_vector_error", lastVectorError.get(),
                "fusion", "weighted_rrf",
                "rrf_k", properties.getRag().getRrfK()
        );
    }

    private List<SearchResult> rrfFuse(List<SearchResult> bm25Results,
                                       List<SearchResult> vectorResults,
                                       int topK) {
        Map<String, SearchResult> candidates = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Integer> bm25Ranks = ranks(bm25Results);
        Map<String, Integer> vectorRanks = ranks(vectorResults);
        int rrfK = Math.max(1, properties.getRag().getRrfK());

        for (int i = 0; i < bm25Results.size(); i++) {
            SearchResult result = bm25Results.get(i);
            candidates.putIfAbsent(result.id(), result);
            rrfScores.merge(result.id(), properties.getRag().getBm25Weight() / (rrfK + i + 1), Double::sum);
        }
        for (int i = 0; i < vectorResults.size(); i++) {
            SearchResult result = vectorResults.get(i);
            candidates.putIfAbsent(result.id(), result);
            rrfScores.merge(result.id(), properties.getRag().getVectorWeight() / (rrfK + i + 1), Double::sum);
        }

        Map<String, Double> normalized = normalize(rrfScores);
        return candidates.values().stream()
                .sorted(Comparator.comparingDouble((SearchResult result) -> rrfScores.getOrDefault(result.id(), 0.0)).reversed())
                .limit(topK)
                .map(result -> withRetrievalMetadata(
                        result,
                        round(normalized.getOrDefault(result.id(), 0.0)),
                        MODE_HYBRID,
                        bm25Ranks.get(result.id()),
                        vectorRanks.get(result.id())
                ))
                .toList();
    }

    private List<SearchResult> decorateSingleChannel(List<SearchResult> results,
                                                     String mode,
                                                     String channel,
                                                     int topK) {
        return results.stream()
                .limit(topK)
                .map(result -> withRetrievalMetadata(
                        result,
                        result.score(),
                        mode,
                        "bm25".equals(channel) ? rankOf(results, result.id()) : null,
                        "vector".equals(channel) ? rankOf(results, result.id()) : null
                ))
                .toList();
    }

    private SearchResult withRetrievalMetadata(SearchResult result,
                                               double score,
                                               String mode,
                                               Integer bm25Rank,
                                               Integer vectorRank) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("retrieval_mode", mode);
        if (bm25Rank != null) {
            metadata.put("bm25_rank", bm25Rank);
        }
        if (vectorRank != null) {
            metadata.put("vector_rank", vectorRank);
        }
        return new SearchResult(result.id(), result.title(), result.content(), round(score), result.chunk(), Map.copyOf(metadata));
    }

    private Map<String, Integer> ranks(List<SearchResult> results) {
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < results.size(); i++) {
            ranks.putIfAbsent(results.get(i).id(), i + 1);
        }
        return ranks;
    }

    private Integer rankOf(List<SearchResult> results, String id) {
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).id().equals(id)) {
                return i + 1;
            }
        }
        return null;
    }

    private List<SearchResult> bm25Search(String query, int topK) {
        Map<String, Double> scores = bm25Scores(query);
        return documents.stream()
                .map(doc -> new SearchResult(
                        doc.id(),
                        doc.title(),
                        doc.content(),
                        round(scores.getOrDefault(doc.id(), 0.0)),
                        doc.chunkIndex(),
                        doc.metadata()
                ))
                .filter(result -> result.score() > 0.0)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    private VectorSearchOutcome vectorSearch(String query, int topK) {
        if (!properties.getRag().isVectorEnabled()) {
            vectorHealthy.set(false);
            lastVectorError.set("Vector retrieval is disabled");
            return new VectorSearchOutcome(false, List.of());
        }
        try {
            VectorStore vectorStore = requireVectorStore();
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(properties.getRag().getSimilarityThreshold())
                    .build();
            List<org.springframework.ai.document.Document> matches = vectorStore.similaritySearch(request);
            List<SearchResult> results = matches == null ? List.of() : matches.stream()
                    .map(this::toSearchResult)
                    .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                    .toList();
            vectorHealthy.set(true);
            lastVectorError.set("");
            return new VectorSearchOutcome(true, results);
        } catch (Exception ex) {
            vectorHealthy.set(false);
            lastVectorError.set(message(ex));
            log.warn("Chroma vector retrieval failed; falling back to BM25-only: {}", message(ex));
            return new VectorSearchOutcome(false, List.of());
        }
    }

    private SearchResult toSearchResult(org.springframework.ai.document.Document document) {
        Map<String, Object> metadata = document.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(document.getMetadata());
        String title = String.valueOf(metadata.getOrDefault("title", "未命名文档"));
        int chunkIndex = integerValue(metadata.get("chunk_index"));
        double score = document.getScore() == null ? 0.0 : document.getScore();
        return new SearchResult(
                document.getId(),
                title,
                document.getText() == null ? "" : document.getText(),
                round(Math.max(0.0, score)),
                chunkIndex,
                Map.copyOf(metadata)
        );
    }

    private int integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private List<TextSegment> split(String content) {
        try {
            return splitter.split(Document.from(content == null ? "" : content));
        } catch (Exception ex) {
            log.warn("LangChain4j splitter failed, using simple splitter: {}", ex.getMessage());
            return simpleSplit(content);
        }
    }

    private List<TextSegment> simpleSplit(String content) {
        String text = content == null ? "" : content;
        List<TextSegment> segments = new ArrayList<>();
        for (int start = 0; start < text.length(); start += 500) {
            segments.add(TextSegment.from(text.substring(start, Math.min(text.length(), start + 500))));
        }
        return segments;
    }

    private void replaceInVectorStore(List<String> replacedIds, List<KnowledgeDocument> newChunks) {
        if (!properties.getRag().isVectorEnabled()) {
            return;
        }
        try {
            VectorStore vectorStore = requireVectorStore();
            if (!replacedIds.isEmpty()) {
                vectorStore.delete(replacedIds);
            }
            syncVectorStore(newChunks);
        } catch (Exception ex) {
            vectorHealthy.set(false);
            lastVectorError.set(message(ex));
            log.warn("Chroma document replacement failed; local BM25 index remains available: {}", message(ex));
        }
    }

    private void syncVectorStore(List<KnowledgeDocument> chunks) {
        if (!properties.getRag().isVectorEnabled() || chunks.isEmpty()) {
            return;
        }
        try {
            VectorStore vectorStore = requireVectorStore();
            List<org.springframework.ai.document.Document> springDocuments = chunks.stream()
                    .map(doc -> new org.springframework.ai.document.Document(doc.id(), doc.content(), doc.metadata()))
                    .toList();
            vectorStore.add(springDocuments);
            vectorHealthy.set(true);
            lastVectorError.set("");
            log.info("Upserted {} knowledge chunks into ChromaDB", chunks.size());
        } catch (Exception ex) {
            vectorHealthy.set(false);
            lastVectorError.set(message(ex));
            log.warn("Chroma synchronization failed; local BM25 index remains available: {}", message(ex));
        }
    }

    private VectorStore requireVectorStore() {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            throw new IllegalStateException("VectorStore bean is not configured");
        }
        return vectorStore;
    }

    private void loadPersistedDocuments() {
        Path path = Path.of(properties.getStorage().getKnowledgePath());
        if (!Files.exists(path)) {
            return;
        }
        try {
            List<StoredKnowledgeDocument> stored = objectMapper.readValue(path.toFile(), new TypeReference<>() {
            });
            for (StoredKnowledgeDocument item : stored) {
                if (item.content() == null || item.content().isBlank()) {
                    continue;
                }
                String title = item.title() == null || item.title().isBlank() ? "未命名文档" : item.title();
                Map<String, Object> metadata = migrateMetadata(item, title);
                KnowledgeDocument doc = new KnowledgeDocument(
                        item.id() == null || item.id().isBlank() ? md5(title + item.chunkIndex() + item.content()) : item.id(),
                        title,
                        item.content(),
                        item.chunkIndex(),
                        metadata
                );
                documents.removeIf(existing -> existing.id().equals(doc.id()));
                documents.add(doc);
            }
            log.info("Loaded {} persisted knowledge chunks from {}", documents.size(), path);
        } catch (Exception ex) {
            log.warn("Failed to load persisted knowledge store {}: {}", path, ex.getMessage());
        }
    }

    private Map<String, Object> migrateMetadata(StoredKnowledgeDocument item, String title) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (item.metadata() != null) {
            metadata.putAll(item.metadata());
        }
        String source = String.valueOf(metadata.getOrDefault("source", "echomind-java"));
        metadata.putIfAbsent("title", title);
        metadata.putIfAbsent("chunk_index", item.chunkIndex());
        metadata.putIfAbsent("source", source);
        metadata.putIfAbsent("version", "v1");
        metadata.putIfAbsent("document_key", md5(source + "|" + title));
        return Map.copyOf(metadata);
    }

    private void persistDocuments() {
        Path path = Path.of(properties.getStorage().getKnowledgePath());
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            List<StoredKnowledgeDocument> stored = documents.stream()
                    .map(doc -> new StoredKnowledgeDocument(doc.id(), doc.title(), doc.content(), doc.chunkIndex(), doc.metadata()))
                    .toList();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), stored);
        } catch (Exception ex) {
            log.warn("Failed to persist knowledge store {}: {}", path, ex.getMessage());
        }
    }

    private Map<String, Double> bm25Scores(String query) {
        List<String> queryTerms = tokenize(query);
        Map<String, Double> scores = new HashMap<>();
        if (queryTerms.isEmpty() || documents.isEmpty()) {
            return scores;
        }
        Map<String, List<String>> documentTerms = new HashMap<>();
        for (KnowledgeDocument document : documents) {
            documentTerms.put(document.id(), tokenize(document.content()));
        }
        double avgDl = documentTerms.values().stream().mapToInt(List::size).average().orElse(1.0);
        double k1 = 1.5;
        double b = 0.75;
        for (KnowledgeDocument doc : documents) {
            List<String> terms = documentTerms.get(doc.id());
            double score = 0.0;
            for (String term : queryTerms) {
                long tf = terms.stream().filter(term::equals).count();
                if (tf == 0) {
                    continue;
                }
                long df = documentTerms.values().stream().filter(values -> values.contains(term)).count();
                double idf = Math.log(1 + (documents.size() - df + 0.5) / (df + 0.5));
                double denom = tf + k1 * (1 - b + b * terms.size() / avgDl);
                score += idf * (tf * (k1 + 1)) / denom;
            }
            scores.put(doc.id(), score);
        }
        return normalize(scores);
    }

    private Map<String, Double> normalize(Map<String, Double> scores) {
        double max = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        if (max <= 0.0) {
            return scores;
        }
        Map<String, Double> normalized = new HashMap<>();
        scores.forEach((id, score) -> normalized.put(id, score / max));
        return normalized;
    }

    private List<String> tokenize(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", " ");
        List<String> tokens = new ArrayList<>();
        for (String part : normalized.split("\\s+")) {
            if (!part.isBlank()) {
                tokens.add(part);
            }
        }
        for (int n = 2; n <= 3; n++) {
            for (int i = 0; i + n <= normalized.length(); i++) {
                String gram = normalized.substring(i, i + n).trim();
                if (!gram.isBlank()) {
                    tokens.add(gram);
                }
            }
        }
        return tokens;
    }

    private String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private String message(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private List<Map<String, String>> defaultDocuments() {
        List<Map<String, String>> docs = new ArrayList<>();
        docs.add(doc("退款政策", "用户在购买后 7 天内可以申请无理由退款。退款申请提交后，系统会在 1-3 个工作日内审核。审核通过后，款项将在 5-7 个工作日内退回原支付账户。商品已发货时，需要先完成退货流程。"));
        docs.add(doc("订单查询", "用户可以通过订单号查询订单状态。订单状态包括待支付、已支付、已发货、运输中、已签收、已完成。物流信息通常在发货后 24 小时内更新。"));
        docs.add(doc("账户安全", "建议用户定期修改密码，密码长度至少 8 位，包含字母和数字。如果忘记密码，可以通过绑定手机号或邮箱重置。发现异常登录时系统会锁定账户。"));
        docs.add(doc("技术故障排查", "应用崩溃请尝试清除缓存后重启应用。登录失败 401 表示认证失败。500 服务器错误通常是服务端问题，请稍后重试或联系技术支持。"));
        docs.add(doc("会员与积分", "每消费 1 元累积 1 积分。100 积分可抵扣 1 元。会员等级包括普通会员、银卡会员和金卡会员，积分有效期为 1 年。"));
        docs.add(doc("配送说明", "标准配送 3-5 个工作日送达，订单满 99 元免运费。加急配送 1-2 个工作日送达。同城配送可当日达或次日达。"));
        return docs;
    }

    private Map<String, String> doc(String title, String content) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("content", content);
        return map;
    }

    private record VectorSearchOutcome(boolean available, List<SearchResult> results) {
    }

    private record StoredKnowledgeDocument(
            String id,
            String title,
            String content,
            int chunkIndex,
            Map<String, Object> metadata
    ) {
    }
}
