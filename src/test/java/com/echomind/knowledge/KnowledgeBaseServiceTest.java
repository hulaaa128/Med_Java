package com.echomind.knowledge;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceTest {

    @TempDir
    Path tempDir;

    private VectorStore vectorStore;
    private KnowledgeBaseService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getStorage().setKnowledgePath(tempDir.resolve("knowledge.json").toString());
        properties.getRag().setRecallMultiplier(3);
        properties.getRag().setRrfK(60);
        properties.getRag().setVectorEnabled(true);

        vectorStore = mock(VectorStore.class);
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(vectorStore);

        service = new KnowledgeBaseService(properties, provider, new ObjectMapper());
        service.init();
        reset(vectorStore);
    }

    @Test
    void combinesBm25AndChromaResultsWithWeightedRrf() {
        Document semanticMatch = Document.builder()
                .id("semantic-drug-result")
                .text("审核通过后，退款会退回原支付账户。")
                .metadata(Map.of("title", "退款到账说明", "chunk_index", 0, "source", "chroma-test"))
                .score(0.91)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(semanticMatch));

        List<SearchResult> results = service.search("退款多久能到账", 3);

        assertThat(results).isNotEmpty();
        assertThat(results).anySatisfy(result -> {
            assertThat(result.id()).isEqualTo("semantic-drug-result");
            assertThat(result.metadata()).containsEntry("retrieval_mode", "hybrid_rrf");
            assertThat(result.metadata()).containsKey("vector_rank");
        });
        assertThat(results).anySatisfy(result -> {
            assertThat(result.title()).isEqualTo("退款政策");
            assertThat(result.metadata()).containsEntry("retrieval_mode", "hybrid_rrf");
            assertThat(result.metadata()).containsKey("bm25_rank");
        });

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getQuery()).isEqualTo("退款多久能到账");
        assertThat(requestCaptor.getValue().getTopK()).isEqualTo(9);
        assertThat(service.retrievalStatus())
                .containsEntry("vector_healthy", true)
                .containsEntry("last_retrieval_mode", "hybrid_rrf");
    }

    @Test
    void fallsBackToBm25WhenEmbeddingOrChromaIsUnavailable() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("chroma offline"));

        List<SearchResult> results = service.search("退款审核需要多久", 3);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().title()).isEqualTo("退款政策");
        assertThat(results).allSatisfy(result ->
                assertThat(result.metadata()).containsEntry("retrieval_mode", "bm25_only"));
        assertThat(service.retrievalStatus())
                .containsEntry("vector_healthy", false)
                .containsEntry("last_retrieval_mode", "bm25_only")
                .containsEntry("last_vector_error", "chroma offline");
    }

    @Test
    @SuppressWarnings("unchecked")
    void embedsAndUpsertsChunksWithStableMetadata() {
        int added = service.addDocuments(List.of(Map.of(
                "title", "布洛芬说明书",
                "content", "用于缓解轻至中度疼痛，也可用于普通感冒或流行性感冒引起的发热。",
                "source", "drug-manual",
                "version", "2026-08"
        )));

        ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(documentsCaptor.capture());

        assertThat(added).isEqualTo(1);
        assertThat(documentsCaptor.getValue()).hasSize(1);
        Document chunk = documentsCaptor.getValue().getFirst();
        assertThat(chunk.getId()).isNotBlank();
        assertThat(chunk.getText()).contains("缓解轻至中度疼痛");
        assertThat(chunk.getMetadata())
                .containsEntry("title", "布洛芬说明书")
                .containsEntry("source", "drug-manual")
                .containsEntry("version", "2026-08")
                .containsEntry("chunk_index", 0)
                .containsKey("document_key");
    }
}
