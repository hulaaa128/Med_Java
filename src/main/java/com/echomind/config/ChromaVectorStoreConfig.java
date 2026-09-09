package com.echomind.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "echomind.rag", name = "vector-enabled", havingValue = "true", matchIfMissing = true)
public class ChromaVectorStoreConfig {

    @Bean
    ChromaApi echomindChromaApi(
            @Value("${spring.ai.vectorstore.chroma.client.host:http://localhost}") String host,
            @Value("${spring.ai.vectorstore.chroma.client.port:8002}") int port,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectMapper objectMapper) {
        return ChromaApi.builder()
                .baseUrl(host + ":" + port)
                .restClientBuilder(restClientBuilderProvider.getIfAvailable(RestClient::builder))
                .objectMapper(objectMapper)
                .build();
    }

    @Bean
    @Lazy
    VectorStore echomindChromaVectorStore(
            ChromaApi echomindChromaApi,
            EmbeddingModel embeddingModel,
            @Value("${spring.ai.vectorstore.chroma.collection-name:echomind_knowledge}") String collectionName,
            @Value("${spring.ai.vectorstore.chroma.tenant-name:default_tenant}") String tenantName,
            @Value("${spring.ai.vectorstore.chroma.database-name:default_database}") String databaseName,
            @Value("${spring.ai.vectorstore.chroma.initialize-schema:true}") boolean initializeSchema,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        return ChromaVectorStore.builder(echomindChromaApi, embeddingModel)
                .collectionName(collectionName)
                .tenantName(tenantName)
                .databaseName(databaseName)
                .initializeSchema(initializeSchema)
                .batchingStrategy(new TokenCountBatchingStrategy())
                .observationRegistry(observationRegistryProvider.getIfUnique(() -> ObservationRegistry.NOOP))
                .build();
    }
}
