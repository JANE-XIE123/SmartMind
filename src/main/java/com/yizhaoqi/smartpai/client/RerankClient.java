package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 重排序客户端，调用阿里百炼 qwen3-rerank 模型对候选文档进行精排。
 * 使用 DashScope OpenAI 兼容接口：POST /reranks
 */
@Component
public class RerankClient {

    private static final Logger logger = LoggerFactory.getLogger(RerankClient.class);

    private final ObjectMapper objectMapper;
    private final ModelProviderConfigService modelProviderConfigService;

    @Value("${rerank.api.url:https://dashscope.aliyuncs.com/compatible-api/v1}")
    private String rerankApiUrl;

    @Value("${rerank.api.key:}")
    private String rerankApiKey;

    @Value("${rerank.api.model:qwen3-rerank}")
    private String rerankModel;

    @Value("${rerank.api.enabled:true}")
    private boolean rerankEnabled;

    @Value("${rerank.api.max-docs:5000}")
    private int maxDocs;

    @Value("${rerank.api.timeout-seconds:300}")
    private int timeoutSeconds;

    public RerankClient(ObjectMapper objectMapper,
                        ModelProviderConfigService modelProviderConfigService) {
        this.objectMapper = objectMapper;
        this.modelProviderConfigService = modelProviderConfigService;
    }

    public boolean isEnabled() {
        return rerankEnabled;
    }

    /**
     * 对候选文档列表进行重排序，返回按相关性降序排列的结果索引映射。
     *
     * @param query     用户查询
     * @param documents 候选文档列表
     * @param topN      返回前 N 个结果
     * @return 按 rerank 分数降序排列的原始索引列表
     */
    public RerankResult rerank(String query, List<String> documents, int topN) {
        if (!rerankEnabled) {
            logger.debug("重排序未启用，跳过重排序");
            return RerankResult.empty();
        }

        if (documents == null || documents.isEmpty()) {
            logger.debug("候选文档列表为空，跳过重排序");
            return RerankResult.empty();
        }

        if (documents.size() > maxDocs) {
            logger.warn("候选文档数量 {} 超过最大限制 {}，截断处理", documents.size(), maxDocs);
            documents = new ArrayList<>(documents.subList(0, maxDocs));
        }

        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            logger.warn("未配置 rerank API Key，跳过重排序");
            return RerankResult.empty();
        }

        // 构建符合 OpenAI 兼容格式的 Rerank API 请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", rerankModel);
        requestBody.put("query", query);
        requestBody.put("documents", documents);
        requestBody.put("top_n", Math.min(topN, documents.size()));
        // instruct 参数可选，用于提供重排序的指导说明
        // requestBody.put("instruct", "Given a web search query, retrieve relevant passages that answer the query.");

        logger.debug("发送重排序请求 - 模型: {}, 文档数: {}, topN: {}", rerankModel, documents.size(), topN);
        logger.debug("Rerank API URL: {}, 完整路径: {}", rerankApiUrl, rerankApiUrl + "/reranks");

        try {
            String response = buildClient(apiKey).post()
                    .uri("/reranks")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                            .maxBackoff(Duration.ofSeconds(5))
                            .filter(e -> {
                                // 重试 5xx 服务器错误和网络连接异常
                                if (e instanceof WebClientResponseException wcre) {
                                    return wcre.getStatusCode().is5xxServerError();
                                }
                                // 重试连接重置、超时等网络异常
                                return e.getMessage() != null && 
                                       (e.getMessage().contains("Connection reset") || 
                                        e.getMessage().contains("connection") ||
                                        e.getMessage().contains("timeout"));
                            })
                            .doBeforeRetry(signal -> logger.warn("重排序 API 重试 - 第 {} 次, 错误: {}",
                                    signal.totalRetries() + 1, signal.failure().getMessage())))
                    .onErrorMap(WebClientResponseException.class, this::handleWebClientError)
                    .block(Duration.ofSeconds(timeoutSeconds));

            logger.debug("Rerank API 响应: {}", response != null && response.length() > 200 ? response.substring(0, 200) + "..." : response);
            return parseRerankResponse(response, documents.size());
        } catch (Exception e) {
            if (e instanceof WebClientResponseException.Unauthorized) {
                logger.error("重排序 API 认证失败 (401) - 请检查 API Key 是否有效: {}",
                        apiKey != null && apiKey.length() > 10
                                ? apiKey.substring(0, 10) + "..."
                                : "(空)");
            } else {
                logger.error("重排序 API 调用失败: {} - 类型: {}",
                        e.getMessage(), e.getClass().getSimpleName(), e);
            }
            return RerankResult.empty();
        }
    }

    private String resolveApiKey() {
        if (rerankApiKey != null && !rerankApiKey.isBlank()) {
            logger.debug("使用配置的 rerank API Key");
            return rerankApiKey;
        }
        try {
            ModelProviderConfigService.ActiveProviderView provider =
                    modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
            if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
                logger.debug("使用 embedding provider 的 API Key 进行重排序");
                return provider.apiKey();
            } else {
                logger.debug("Embedding provider 的 API Key 为空");
            }
        } catch (Exception e) {
            logger.debug("无法从 embedding provider 获取 API Key: {}", e.getMessage());
        }
        logger.warn("未找到可用的 Rerank API Key，请检查配置");
        return null;
    }

    private WebClient buildClient(String apiKey) {
        return WebClient.builder()
                .baseUrl(rerankApiUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /**
     * 处理 WebClient 错误，区分不同类型的 HTTP 错误
     */
    private Exception handleWebClientError(WebClientResponseException ex) {
        var status = ex.getStatusCode();
        
        if (status.value() == 401) {
            logger.warn("重排序 API 认证失败 (401 Unauthorized) - API Key 可能无效或已过期");
            return new RuntimeException("Rerank API 认证失败：请检查 API Key 配置是否正确", ex);
        } else if (status.value() == 429) {
            logger.warn("重排序 API 请求过于频繁 (429 Too Many Requests)");
            return new RuntimeException("Rerank API 请求频率超限，请稍后重试", ex);
        } else if (status.value() >= 500) {
            logger.warn("重排序 API 服务端错误 ({}): {}", status.value(), ex.getResponseBodyAsString());
            return new RuntimeException("Rerank API 服务端错误: HTTP " + status.value(), ex);
        } else {
            logger.warn("重排序 API 客户端错误 ({}): {}", status.value(), ex.getResponseBodyAsString());
            return new RuntimeException("Rerank API 错误: HTTP " + status.value() + " - " + ex.getResponseBodyAsString(), ex);
        }
    }

    private RerankResult parseRerankResponse(String response, int originalDocCount) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get("results");

            List<RerankHit> hits = new ArrayList<>();
            if (results != null && results.isArray()) {
                for (JsonNode item : results) {
                    int index = item.get("index").asInt();
                    double score = item.get("relevance_score").asDouble();
                    hits.add(new RerankHit(index, score));
                }
            }

            logger.debug("重排序完成 - 返回 {} 条结果", hits.size());
            return new RerankResult(hits);
        } catch (Exception e) {
            logger.error("解析重排序响应失败: {}", e.getMessage());
            return RerankResult.empty();
        }
    }

    public record RerankHit(int originalIndex, double relevanceScore) {}

    public record RerankResult(List<RerankHit> hits) {
        public boolean isEmpty() {
            return hits == null || hits.isEmpty();
        }

        public static RerankResult empty() {
            return new RerankResult(List.of());
        }
    }
}
