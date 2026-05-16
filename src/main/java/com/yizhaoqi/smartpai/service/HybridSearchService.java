package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.client.RerankClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.model.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.util.ObjectBuilder;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/**
 * 混合搜索服务，结合关键词检索 (BM25)、向量检索 (KNN)、RRF 融合和重排序。
 * 支持权限过滤，确保用户只能搜索其有权限访问的文档。
 *
 * 检索流程：
 * 用户查询 → BM25 关键词检索 + KNN 向量检索 → RRF 融合 → Reranker 精排 → TopK
 * 失败时降级到原有混合搜索（KNN + BM25 MUST + Rescore）
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);
    private static final String INDEX_NAME = "knowledge_base";
    private static final int RRF_K = 60; // RRF 融合常数

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private RerankClient rerankClient;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private QueryAnalyzer queryAnalyzer;

    @Value("${rerank.api.enabled:true}")
    private boolean rerankEnabled;

    /**
     * 主搜索入口：优先使用 RRF+Rerank 管线，失败时降级到现有混合搜索。
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("开始带权限搜索，查询: {}, 用户ID: {}, topK: {}", query, userId, topK);

        try {
            return searchWithRerankPipeline(query, userId, topK);
        } catch (Exception e) {
            logger.error("Rerank 管线失败，降级到现有混合搜索: {}", e.getMessage(), e);
            try {
                return hybridSearchWithPermission(query, userId, topK);
            } catch (Exception fallbackError) {
                logger.error("降级混合搜索也失败", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    /**
     * RRF + Rerank 搜索管线：
     * 1. 独立 BM25 关键词检索
     * 2. 独立 KNN 向量检索
     * 3. RRF 融合
     * 4. Reranker 精排
     * 5. 取 TopK
     */
    private List<SearchResult> searchWithRerankPipeline(String query, String userId, int topK) {
        String userDbId = getUserDbId(userId);
        List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
        int recallK = topK * 30;

        // 1. 独立 BM25 关键词检索
        List<SearchResult> bm25Results = bm25SearchWithPermission(query, userDbId, userEffectiveTags, recallK);
        logger.debug("BM25 检索完成，结果数: {}", bm25Results.size());

        // 2. 生成查询向量
        List<Float> queryVector = embedToVectorList(query, userId);
        List<SearchResult> knnResults;
        if (queryVector != null) {
            knnResults = knnSearchWithPermission(query, queryVector, userDbId, userEffectiveTags, recallK);
            logger.debug("KNN 检索完成，结果数: {}", knnResults.size());
        } else {
            logger.warn("查询向量生成失败，KNN 检索降级为空");
            knnResults = Collections.emptyList();
        }

        // 如果两条路径都无结果，直接返回
        if (bm25Results.isEmpty() && knnResults.isEmpty()) {
            logger.debug("BM25 和 KNN 均无结果");
            return Collections.emptyList();
        }

        // 3. 查询分析 + RRF 融合
        QueryAnalyzer.QueryWeights weights = queryAnalyzer.analyze(query);
        List<SearchResult> fused = rrfFusion(bm25Results, knnResults, RRF_K, weights.bm25Weight(), weights.knnWeight());
        logger.debug("RRF 融合完成，结果数: {}, bm25Weight={}, knnWeight={}",
                fused.size(), String.format("%.2f", weights.bm25Weight()), String.format("%.2f", weights.knnWeight()));

        // 4. Reranker 精排
        List<SearchResult> reranked = applyRerank(query, fused, topK);
        logger.debug("Rerank 精排完成，最终结果数: {}", reranked.size());

        attachFileNames(reranked);
        return reranked;
    }

    /**
     * 独立 BM25 关键词检索（无 KNN，无 rescore）
     */
    private List<SearchResult> bm25SearchWithPermission(String query, String userDbId,
                                                         List<String> userEffectiveTags, int recallK) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index(INDEX_NAME)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.match(ma -> ma.field("textContent").query(query)))
                            .filter(f -> buildPermissionFilter(f, userDbId, userEffectiveTags))
                    ))
                    .size(recallK),
                    EsDocument.class);

            return mapHits(response, "BM25");
        } catch (Exception e) {
            logger.error("BM25 检索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 独立 KNN 向量检索（无 BM25 MUST 过滤，仅权限过滤）
     */
    /**
     * 独立 KNN 向量检索，不做 ES 侧权限过滤以避免 KNN pre-filter 兼容性问题，
     * 权限过滤在 Java 侧完成。
     */
    private List<SearchResult> knnSearchWithPermission(String query, List<Float> queryVector,
                                                        String userDbId, List<String> userEffectiveTags,
                                                        int recallK) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index(INDEX_NAME);
                s.knn(kn -> kn
                        .field("vector")
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(Math.min(recallK * 2, 10000))
                );
                s.size(recallK);
                return s;
            }, EsDocument.class);

            List<SearchResult> results = mapHits(response, "KNN");
            return filterByPermission(results, userDbId, userEffectiveTags);
        } catch (Exception e) {
            logKnnError(e);
            return Collections.emptyList();
        }
    }

    private List<SearchResult> filterByPermission(List<SearchResult> results, String userDbId,
                                                   List<String> userEffectiveTags) {
        if (results.isEmpty()) {
            return results;
        }
        return results.stream()
                .filter(r -> isAccessible(r, userDbId, userEffectiveTags))
                .collect(Collectors.toList());
    }

    private boolean isAccessible(SearchResult r, String userDbId, List<String> userEffectiveTags) {
        if (userDbId != null && userDbId.equals(r.getUserId())) {
            return true;
        }
        if (Boolean.TRUE.equals(r.getIsPublic())) {
            return true;
        }
        if (r.getOrgTag() != null && userEffectiveTags != null
                && userEffectiveTags.contains(r.getOrgTag())) {
            return true;
        }
        return false;
    }

    private void logKnnError(Exception e) {
        if (e instanceof co.elastic.clients.elasticsearch._types.ElasticsearchException esException) {
            String detail = esException.getMessage();
            Throwable cause = esException.getCause();
            while (cause != null) {
                detail = detail + "; caused by: " + cause.getMessage();
                cause = cause.getCause();
            }
            logger.error("KNN 检索失败 - status={}, detail={}", esException.status(), detail);
        } else {
            logger.error("KNN 检索失败: {}", e.getMessage(), e);
        }
    }

    /**
     * RRF (Reciprocal Rank Fusion) 融合两个排序列表，支持动态权重。
     * RRF_score(d) = w_bm25/(k + rank_bm25(d)) + w_knn/(k + rank_knn(d))
     */
    private List<SearchResult> rrfFusion(List<SearchResult> bm25List, List<SearchResult> knnList,
                                          int k, double bm25Weight, double knnWeight) {
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, SearchResult> docMap = new LinkedHashMap<>();

        accumulateRrf(bm25List, k, bm25Weight, rrfScores, docMap);
        accumulateRrf(knnList, k, knnWeight, rrfScores, docMap);

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> {
                    SearchResult r = docMap.get(entry.getKey());
                    r.setScore(entry.getValue());
                    r.setRetrievalMode("RRF");
                    return r;
                })
                .collect(Collectors.toList());
    }

    private void accumulateRrf(List<SearchResult> list, int k, double weight,
                                Map<String, Double> scores, Map<String, SearchResult> docs) {
        for (int i = 0; i < list.size(); i++) {
            SearchResult r = list.get(i);
            String key = r.getFileMd5() + ":" + r.getChunkId();
            double rrfScore = weight / (k + i + 1);
            scores.merge(key, rrfScore, Double::sum);
            docs.putIfAbsent(key, r);
        }
    }

    /**
     * 调用 Reranker 对融合结果精排，返回 TopK。
     * 如果 reranker 未启用或失败，返回 RRF 融合结果的前 topK。
     */
    private List<SearchResult> applyRerank(String query, List<SearchResult> fused, int topK) {
        if (!rerankEnabled || fused.isEmpty()) {
            int limit = Math.min(topK, fused.size());
            return new ArrayList<>(fused.subList(0, limit));
        }

        // 准备文档文本列表
        List<String> documents = fused.stream()
                .map(r -> r.getMatchedChunkText() != null ? r.getMatchedChunkText() : r.getTextContent())
                .collect(Collectors.toList());

        RerankClient.RerankResult rerankResult = rerankClient.rerank(query, documents, topK);

        if (rerankResult.isEmpty()) {
            logger.warn("Reranker 返回空结果，使用 RRF 融合结果");
            return new ArrayList<>(fused.subList(0, Math.min(topK, fused.size())));
        }

        // 按 rerank 分数重建排序列表
        List<SearchResult> reranked = new ArrayList<>();
        for (RerankClient.RerankHit hit : rerankResult.hits()) {
            if (hit.originalIndex() >= 0 && hit.originalIndex() < fused.size()) {
                SearchResult r = fused.get(hit.originalIndex());
                r.setScore(hit.relevanceScore());
                r.setRetrievalMode("RERANK");
                reranked.add(r);
            }
        }

        return reranked;
    }

    /**
     * 现有混合搜索（KNN + BM25 MUST + Rescore），作为降级方案。
     */
    private List<SearchResult> hybridSearchWithPermission(String query, String userId, int topK) {
        logger.debug("执行原有混合搜索（降级方案），查询: {}, 用户ID: {}", query, userId);

        List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
        String userDbId = getUserDbId(userId);

        List<Float> queryVector = embedToVectorList(query, userId);
        if (queryVector == null) {
            logger.warn("向量生成失败，仅使用文本匹配进行搜索");
            return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK);
        }

        try {
            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index(INDEX_NAME);
                int recallK = topK * 30;
                s.knn(kn -> kn
                        .field("vector")
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(Math.min(recallK * 2, 10000))
                );
                s.query(q -> q.bool(b -> b
                        .must(mst -> mst.match(m -> m.field("textContent").query(query)))
                        .filter(f -> buildPermissionFilter(f, userDbId, userEffectiveTags))
                ));
                s.rescore(r -> r
                        .windowSize(recallK)
                        .query(rq -> rq
                                .queryWeight(0.2d)
                                .rescoreQueryWeight(1.0d)
                                .query(rqq -> rqq.match(m -> m
                                        .field("textContent")
                                        .query(query)
                                        .operator(Operator.And)
                                ))
                        )
                );
                s.size(topK);
                return s;
            }, EsDocument.class);

            List<SearchResult> results = mapHits(response, "HYBRID");
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("原有混合搜索失败", e);
            try {
                return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK);
            } catch (Exception fallbackError) {
                logger.error("纯文本后备搜索也失败", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    /**
     * 构建权限过滤条件，直接操作 Query.Builder 并返回 ObjectBuilder<Query>
     */
    private ObjectBuilder<co.elastic.clients.elasticsearch._types.query_dsl.Query> buildPermissionFilter(
            co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder qb,
            String userDbId, List<String> userEffectiveTags) {
        return qb.bool(bf -> {
            bf.should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
              .should(s2 -> s2.term(t -> t.field("public").value(true)))
              .should(s3 -> {
                  if (userEffectiveTags.isEmpty()) {
                      return s3.matchNone(mn -> mn);
                  } else if (userEffectiveTags.size() == 1) {
                      return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
                  } else {
                      return s3.bool(inner -> {
                          userEffectiveTags.forEach(tag ->
                                  inner.should(sh2 -> sh2.term(t -> t.field("orgTag").value(tag))));
                          return inner;
                      });
                  }
              });
            return bf;
        });
    }

    /**
     * 仅使用文本匹配的带权限搜索方法
     */
    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId,
                                                             List<String> userEffectiveTags, int topK) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index(INDEX_NAME)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.match(ma -> ma.field("textContent").query(query)))
                            .filter(f -> buildPermissionFilter(f, userDbId, userEffectiveTags))
                    ))
                    .minScore(0.3d)
                    .size(topK),
                    EsDocument.class);

            List<SearchResult> results = mapHits(response, "TEXT_ONLY");
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 原始搜索方法，不包含权限过滤（保留向后兼容性）
     */
    public List<SearchResult> search(String query, int topK) {
        try {
            logger.warn("使用了没有权限过滤的搜索方法，建议使用 searchWithPermission 方法");
            return searchWithRerankPipelineNoPermission(query, topK);
        } catch (Exception e) {
            logger.error("无权限搜索失败，降级到原有混合搜索: {}", e.getMessage(), e);
            try {
                return legacyHybridSearch(query, topK);
            } catch (Exception fallbackError) {
                logger.error("降级搜索也失败", fallbackError);
                try {
                    return textOnlySearch(query, topK);
                } catch (Exception lastError) {
                    logger.error("全部搜索失败", lastError);
                    throw new RuntimeException("搜索完全失败", lastError);
                }
            }
        }
    }

    private List<SearchResult> searchWithRerankPipelineNoPermission(String query, int topK) {
        int recallK = topK * 30;

        List<SearchResult> bm25Results = bm25SearchNoPermission(query, recallK);
        List<Float> queryVector = embedToVectorList(query, "system");
        List<SearchResult> knnResults;
        if (queryVector != null) {
            knnResults = knnSearchNoPermission(query, queryVector, recallK);
        } else {
            knnResults = Collections.emptyList();
        }

        if (bm25Results.isEmpty() && knnResults.isEmpty()) {
            return Collections.emptyList();
        }

        QueryAnalyzer.QueryWeights weights = queryAnalyzer.analyze(query);
        List<SearchResult> fused = rrfFusion(bm25Results, knnResults, RRF_K, weights.bm25Weight(), weights.knnWeight());
        return applyRerank(query, fused, topK);
    }

    private List<SearchResult> bm25SearchNoPermission(String query, int recallK) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index(INDEX_NAME)
                    .query(q -> q.match(m -> m.field("textContent").query(query)))
                    .size(recallK),
                    EsDocument.class);
            return mapHits(response, "BM25");
        } catch (Exception e) {
            logger.error("无权限 BM25 检索失败", e);
            return Collections.emptyList();
        }
    }

    private List<SearchResult> knnSearchNoPermission(String query, List<Float> queryVector, int recallK) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index(INDEX_NAME);
                s.knn(kn -> kn
                        .field("vector")
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(Math.min(recallK * 2, 10000))
                );
                s.size(recallK);
                return s;
            }, EsDocument.class);
            return mapHits(response, "KNN");
        } catch (Exception e) {
            logger.error("无权限 KNN 检索失败", e);
            return Collections.emptyList();
        }
    }

    private List<SearchResult> legacyHybridSearch(String query, int topK) {
        try {
            List<Float> queryVector = embedToVectorList(query, "system");
            if (queryVector == null) {
                return textOnlySearch(query, topK);
            }

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index(INDEX_NAME);
                int recallK = topK * 30;
                s.knn(kn -> kn
                        .field("vector")
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(Math.min(recallK * 2, 10000))
                );
                s.query(q -> q.match(m -> m.field("textContent").query(query)));
                s.rescore(r -> r
                        .windowSize(recallK)
                        .query(rq -> rq
                                .queryWeight(0.2d)
                                .rescoreQueryWeight(1.0d)
                                .query(rqq -> rqq.match(m -> m
                                        .field("textContent")
                                        .query(query)
                                        .operator(Operator.And)
                                ))
                        )
                );
                s.size(topK);
                return s;
            }, EsDocument.class);

            return mapHits(response, "HYBRID");
        } catch (Exception e) {
            logger.error("原有混合搜索（无权限）失败", e);
            throw new RuntimeException("原有混合搜索失败", e);
        }
    }

    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                .index(INDEX_NAME)
                .query(q -> q.match(m -> m.field("textContent").query(query)))
                .size(topK),
                EsDocument.class);
        return mapHits(response, "TEXT_ONLY");
    }

    /**
     * 统一映射 ES 响应到 SearchResult 列表
     */
    private List<SearchResult> mapHits(SearchResponse<EsDocument> response, String retrievalMode) {
        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score(),
                            hit.source().getUserId(),
                            hit.source().getOrgTag(),
                            hit.source().isPublic(),
                            null,
                            hit.source().getPageNumber(),
                            hit.source().getAnchorText(),
                            retrievalMode,
                            hit.source().getTextContent()
                    );
                })
                .collect(Collectors.toList());
    }

    /**
     * 生成查询向量，失败时返回 null
     */
    private List<Float> embedToVectorList(String text, String requesterId) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text), requesterId, EmbeddingClient.UsageType.QUERY);
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("生成的向量为空");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }

    private List<String> getUserEffectiveOrgTags(String userId) {
        try {
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                user = userRepository.findById(userIdLong)
                        .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
            } catch (NumberFormatException e) {
                user = userRepository.findByUsername(userId)
                        .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
            }
            return orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private String getUserDbId(String userId) {
        try {
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                user = userRepository.findById(userIdLong)
                        .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                return userIdLong.toString();
            } catch (NumberFormatException e) {
                user = userRepository.findByUsername(userId)
                        .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                return user.getId().toString();
            }
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName,
                            (existing, replacement) -> existing));
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }
}
