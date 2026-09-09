package com.echomind.memory;

import com.echomind.config.EchoMindProperties;
import com.echomind.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryManagerTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOperations;
    private ValueOperations<String, String> valueOperations;
    private LlmGateway llmGateway;
    private VectorStore episodicVectorStore;
    private VectorStore profileVectorStore;
    private MemoryManager memoryManager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        listOperations = mock(ListOperations.class);
        valueOperations = mock(ValueOperations.class);
        llmGateway = mock(LlmGateway.class);
        episodicVectorStore = mock(VectorStore.class);
        profileVectorStore = mock(VectorStore.class);
        ObjectProvider<VectorStore> episodicProvider = mock(ObjectProvider.class);
        ObjectProvider<VectorStore> profileProvider = mock(ObjectProvider.class);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(episodicProvider.getIfAvailable()).thenReturn(episodicVectorStore);
        when(profileProvider.getIfAvailable()).thenReturn(profileVectorStore);
        when(listOperations.range(anyString(), anyLong(), anyLong())).thenReturn(List.of());

        EchoMindProperties properties = new EchoMindProperties();
        properties.getStorage().setMemoryPath(tempDir.resolve("memory.json").toString());
        memoryManager = new MemoryManager(redisTemplate, objectMapper, properties, llmGateway,
                episodicProvider, profileProvider);
    }

    @Test
    void loadsEpisodicMemoryAndProfileFromSeparateVectorStores() {
        Document episodic = Document.builder()
                .id("episodic-1")
                .text("用户上次咨询了退款进度。")
                .metadata(Map.of("user_id", "u1", "memory_type", "episodic"))
                .score(0.9)
                .build();
        Document profile = Document.builder()
                .id("profile-1")
                .text("{\"preferences\":[\"短信通知\"],\"entities\":{}}")
                .metadata(Map.of("user_id", "u1", "memory_type", "user_profile"))
                .score(1.0)
                .build();
        when(episodicVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(episodic));
        when(profileVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(profile));

        MemoryContext context = memoryManager.getContext("u1", "c1", "我的退款怎么样了？");

        assertThat(context.relevantHistory()).containsExactly("用户上次咨询了退款进度。");
        assertThat(context.userProfile()).containsEntry("preferences", List.of("短信通知"));
        verify(episodicVectorStore).similaritySearch(any(SearchRequest.class));
        verify(profileVectorStore).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesCompressedMessagesToEpisodicCollection() throws Exception {
        List<String> storedMessages = new ArrayList<>();
        for (int i = 14; i >= 0; i--) {
            storedMessages.add(objectMapper.writeValueAsString(new ConversationMessage(
                    i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT,
                    "message-" + i,
                    Instant.now(),
                    Map.of()
            )));
        }
        when(listOperations.size(anyString())).thenReturn(15L);
        when(listOperations.range(anyString(), anyLong(), anyLong())).thenReturn(storedMessages);
        when(llmGateway.chat(anyString(), anyString(), anyDouble(), anyInt())).thenReturn("历史对话摘要");

        memoryManager.addMessage("u1", "c1", MessageRole.USER, "new-message");

        org.mockito.ArgumentCaptor<List<Document>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(episodicVectorStore).add(captor.capture());
        Document stored = captor.getValue().getFirst();
        assertThat(stored.getText()).isEqualTo("历史对话摘要");
        assertThat(stored.getMetadata())
                .containsEntry("memory_type", "episodic")
                .containsEntry("user_id", "u1")
                .containsEntry("conversation_id", "c1")
                .containsEntry("message_count", 10L);
        verify(redisTemplate).delete("wm:u1:c1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesStructuredProfileToProfileCollection() throws Exception {
        String rawMessage = objectMapper.writeValueAsString(new ConversationMessage(
                MessageRole.USER, "我喜欢短信通知", Instant.now(), Map.of()));
        when(listOperations.range(anyString(), anyLong(), anyLong())).thenReturn(List.of(rawMessage));
        when(llmGateway.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"preferences\":[\"短信通知\"],\"entities\":{\"产品\":[],\"问题类型\":[]}}");

        memoryManager.updateProfile("u1", "c1");

        org.mockito.ArgumentCaptor<List<Document>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(profileVectorStore).add(captor.capture());
        Document stored = captor.getValue().getFirst();
        assertThat(stored.getText()).contains("短信通知");
        assertThat(stored.getMetadata())
                .containsEntry("memory_type", "user_profile")
                .containsEntry("user_id", "u1");
    }
}
