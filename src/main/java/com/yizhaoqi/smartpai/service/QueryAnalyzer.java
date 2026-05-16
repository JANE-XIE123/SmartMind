package com.yizhaoqi.smartpai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 *
 *
 *
 *
 * 分析维度：
 * - 查询长度：短查询倾向关键词匹配(BM25)，长查询倾向语义匹配(KNN)
 * - 疑问词：口语化问题倾向语义匹配(KNN)
 * - 专业术语/英文：精确术语倾向关键词匹配(BM25)
 * - 分隔符：逗号/顿号分隔的关键词列表倾向 BM25
 * - 问号结尾：自然语言问题倾向 KNN
 */
@Service
public class QueryAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(QueryAnalyzer.class);

    private static final Set<String> QUESTION_WORDS = Set.of(
            "什么", "怎么", "如何", "为什么", "为何", "哪", "谁", "吗", "呢", "吧",
            "怎样", "怎么样", "多少", "几", "能否", "是否", "可不可以",
            "什么是", "怎么做", "告诉我", "请问", "解释", "说明", "介绍一下"
    );

    @Value("${search.rrf.weight.bm25-default:1.0}")
    private double bm25DefaultWeight;

    @Value("${search.rrf.weight.knn-default:1.0}")
    private double knnDefaultWeight;

    @Value("${search.rrf.weight.max-adjust:0.5}")
    private double maxAdjust;

    /**
     * 分析查询文本，返回 BM25 和 KNN 的 RRF 融合权重。
     */
    public QueryWeights analyze(String query) {
        if (query == null || query.isBlank()) {
            return new QueryWeights(bm25DefaultWeight, knnDefaultWeight);
        }

        String trimmed = query.trim();
        double bias = computeBias(trimmed);

        double bm25Weight = clamp(bm25DefaultWeight + bias, 0.5, 1.5);
        double knnWeight = clamp(knnDefaultWeight - bias, 0.5, 1.5);

        logger.debug("查询分析: query='{}', bm25Weight={}, knnWeight={}, bias={}",
                trimmed.length() > 50 ? trimmed.substring(0, 50) + "..." : trimmed,
                String.format("%.2f", bm25Weight),
                String.format("%.2f", knnWeight),
                String.format("%.2f", bias));

        return new QueryWeights(bm25Weight, knnWeight);
    }

    private double computeBias(String query) {
        double bias = 0.0;

        // 1. 长度因子：短查询 → BM25，长查询 → KNN
        int length = query.length();
        if (length <= 8) {
            bias += 0.3;
        } else if (length <= 15) {
            bias += 0.1;
        } else if (length >= 40) {
            bias -= 0.3;
        } else if (length >= 25) {
            bias -= 0.1;
        }

        // 2. 疑问词检测：口语化问题 → KNN
        for (String qw : QUESTION_WORDS) {
            if (query.contains(qw)) {
                bias -= 0.3;
                break;
            }
        }

        // 3. 英文/专业术语检测：拉丁字符占比高 → BM25（精确匹配更重要）
        int latinChars = 0;
        for (char c : query.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                latinChars++;
            }
        }
        if (length > 0 && (double) latinChars / length > 0.15) {
            bias += 0.2;
        }

        // 4. 关键词列表检测：逗号/顿号分隔 → BM25
        if (query.contains(",") || query.contains("，") || query.contains("、")) {
            bias += 0.15;
        }

        // 5. 问号结尾 → 自然语言问题，倾向 KNN
        if (query.endsWith("？") || query.endsWith("?")) {
            bias -= 0.1;
        }

        return Math.max(-maxAdjust, Math.min(maxAdjust, bias));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 查询分析结果：BM25 和 KNN 在 RRF 融合中的权重。
     */
    public record QueryWeights(double bm25Weight, double knnWeight) {
    }
}
