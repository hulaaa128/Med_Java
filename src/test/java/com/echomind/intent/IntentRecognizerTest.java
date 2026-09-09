package com.echomind.intent;

import com.echomind.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentRecognizerTest {

    private LlmGateway llmGateway;
    private EmbeddingModel embeddingModel;
    private IntentRecognizer recognizer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        llmGateway = mock(LlmGateway.class);
        embeddingModel = mock(EmbeddingModel.class);
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(embeddingModel);
        recognizer = new IntentRecognizer(llmGateway, new ObjectMapper(), provider);
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesEmbeddingSimilarityAndCachesTemplateVectors() {
        when(llmGateway.chat(anyString(), anyString(), eq(0.1), anyInt()))
                .thenThrow(new IllegalStateException("llm offline"));
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(this::templateVector).toList();
        });
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});

        IntentResult first = recognizer.recognize("退款多久到账？", null);
        IntentResult second = recognizer.recognize("退款到账要等多久", null);

        assertThat(first.intent()).isEqualTo(IntentCategory.REFUND);
        assertThat(first.sourceScores())
                .containsEntry("embedding", 1.0)
                .doesNotContainKey("ngram_fallback");
        assertThat(second.intent()).isEqualTo(IntentCategory.REFUND);
        verify(embeddingModel, times(1)).embed(anyList());
        verify(embeddingModel, times(2)).embed(anyString());
    }

    @Test
    void fallsBackToCharNgramsWhenEmbeddingIsUnavailable() {
        when(llmGateway.chat(anyString(), anyString(), eq(0.1), anyInt()))
                .thenThrow(new IllegalStateException("llm offline"));
        when(embeddingModel.embed(anyString()))
                .thenThrow(new IllegalStateException("ollama offline"));

        IntentResult result = recognizer.recognize("物流一直不更新", null);

        assertThat(result.intent()).isEqualTo(IntentCategory.LOGISTICS);
        assertThat(result.sourceScores())
                .containsEntry("embedding", 0.0)
                .containsEntry("ngram_fallback", 1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsOtherWhenOnlyLowConfidenceFallbackIsAvailable() {
        when(llmGateway.chat(anyString(), anyString(), eq(0.1), anyInt()))
                .thenThrow(new IllegalStateException("llm offline"));
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(ignored -> new float[]{1.0f, 0.0f}).toList();
        });
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.2f, 1.0f});

        IntentResult result = recognizer.recognize("请说明一下", null);

        assertThat(result.intent()).isEqualTo(IntentCategory.OTHER);
        assertThat(result.confidence()).isLessThan(0.5);
    }

    private float[] templateVector(String text) {
        return "退款多久到账？".equals(text)
                ? new float[]{1.0f, 0.0f}
                : new float[]{0.0f, 1.0f};
    }
}
