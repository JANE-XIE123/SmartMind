package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.MemoryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MemoryService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryService.class);
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Duration WORKING_MEMORY_TTL = Duration.ofDays(7);
    private static final Duration SUMMARY_TTL = Duration.ofDays(30);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final DeepSeekClient deepSeekClient;
    private final MemoryConfig memoryConfig;
    private final ThreadPoolTaskExecutor memoryExecutor;

    public MemoryService(RedisTemplate<String, String> redisTemplate,
                         ObjectMapper objectMapper,
                         DeepSeekClient deepSeekClient,
                         MemoryConfig memoryConfig,
                         @Qualifier("memoryExecutor") ThreadPoolTaskExecutor memoryExecutor) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.deepSeekClient = deepSeekClient;
        this.memoryConfig = memoryConfig;
        this.memoryExecutor = memoryExecutor;
    }

    // ==================== Tier 1: Working Memory ====================

    public List<Map<String, Object>> getWorkingMemory(String conversationId) {
        return readWorkingMemoryRaw(conversationId);
    }

    public List<Map<String, Object>> appendToWorkingMemory(String conversationId,
                                                            String userMessage,
                                                            String assistantMessage,
                                                            Map<String, Map<String, Object>> referenceMappings) {
        String key = "conversation:" + conversationId;
        List<Map<String, Object>> history = readWorkingMemoryRaw(conversationId);
        List<Map<String, Object>> evicted = new ArrayList<>();

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);

        Map<String, Object> userMsgMap = new HashMap<>();
        userMsgMap.put("role", "user");
        userMsgMap.put("content", userMessage);
        userMsgMap.put("timestamp", timestamp);
        history.add(userMsgMap);

        Map<String, Object> assistantMsgMap = new HashMap<>();
        assistantMsgMap.put("role", "assistant");
        assistantMsgMap.put("content", assistantMessage);
        assistantMsgMap.put("timestamp", timestamp);
        if (referenceMappings != null && !referenceMappings.isEmpty()) {
            assistantMsgMap.put("referenceMappings", referenceMappings);
        }
        history.add(assistantMsgMap);

        int maxMessages = memoryConfig.getWorking().getMaxTurns() * 2;
        if (history.size() > maxMessages) {
            int evictCount = history.size() - maxMessages;
            evicted = new ArrayList<>(history.subList(0, evictCount));
            history = new ArrayList<>(history.subList(evictCount, history.size()));
        }

        try {
            String json = objectMapper.writeValueAsString(history);
            redisTemplate.opsForValue().set(key, json, WORKING_MEMORY_TTL);
            logger.debug("工作记忆更新: conversationId={}, 消息数={}, 淘汰数={}",
                    conversationId, history.size(), evicted.size());
        } catch (JsonProcessingException e) {
            logger.error("工作记忆序列化失败: conversationId={}", conversationId, e);
        }

        return evicted;
    }

    // ==================== Tier 2: Short-term Memory (Summaries) ====================

    public List<String> getSummaryBlocks(String conversationId) {
        Map<String, Object> meta = getSummaryMeta(conversationId);
        int blockCount = 0;
        if (meta.get("blockCount") instanceof Number n) {
            blockCount = n.intValue();
        }

        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < blockCount; i++) {
            String blockKey = summaryBlockKey(conversationId, i);
            String summary = redisTemplate.opsForValue().get(blockKey);
            if (summary != null && !summary.isBlank()) {
                blocks.add(summary);
            }
        }
        return blocks;
    }

    public void storeSummaryBlock(String conversationId, int blockIndex, String summary) {
        String key = summaryBlockKey(conversationId, blockIndex);
        redisTemplate.opsForValue().set(key, summary, SUMMARY_TTL);
    }

    public Map<String, Object> getSummaryMeta(String conversationId) {
        String key = summaryMetaKey(conversationId);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("lastSummarizedMessageIndex", -1);
            empty.put("blockCount", 0);
            empty.put("lastUpdatedAt", "");
            return empty;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            logger.warn("解析摘要元数据失败: conversationId={}", conversationId, e);
            Map<String, Object> empty = new HashMap<>();
            empty.put("lastSummarizedMessageIndex", -1);
            empty.put("blockCount", 0);
            empty.put("lastUpdatedAt", "");
            return empty;
        }
    }

    private void updateSummaryMeta(String conversationId, int lastSummarizedIndex, int blockCount) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("lastSummarizedMessageIndex", lastSummarizedIndex);
        meta.put("blockCount", blockCount);
        meta.put("lastUpdatedAt", LocalDateTime.now().format(TIMESTAMP_FMT));
        try {
            String json = objectMapper.writeValueAsString(meta);
            redisTemplate.opsForValue().set(summaryMetaKey(conversationId), json, SUMMARY_TTL);
        } catch (JsonProcessingException e) {
            logger.error("序列化摘要元数据失败: conversationId={}", conversationId, e);
        }
    }

    public void triggerSummaryIfNeeded(String conversationId, String userId) {
        List<Map<String, Object>> workingMemory = getWorkingMemory(conversationId);
        int totalMessages = workingMemory.size();
        int maxMessages = memoryConfig.getWorking().getMaxTurns() * 2;

        if (totalMessages <= maxMessages) {
            return;
        }

        Map<String, Object> meta = getSummaryMeta(conversationId);
        int lastSummarizedIndex = meta.get("lastSummarizedMessageIndex") instanceof Number n
                ? n.intValue() : -1;
        int blockCount = meta.get("blockCount") instanceof Number n ? n.intValue() : 0;

        int summarizeEnd = totalMessages - maxMessages;
        if (summarizeEnd <= lastSummarizedIndex + 1) {
            return;
        }

        int blockStart = lastSummarizedIndex + 1;
        int blockSize = memoryConfig.getSummary().getBlockSizeTurns() * 2;
        int blockEnd = Math.min(blockStart + blockSize, summarizeEnd);

        List<Map<String, Object>> blockToSummarize = new ArrayList<>(
                workingMemory.subList(blockStart, blockEnd));
        int finalBlockEnd = blockEnd;
        int nextBlockIndex = blockCount;

        memoryExecutor.execute(() -> {
            try {
                String summary = generateConversationSummary(userId, blockToSummarize);
                if (summary == null || summary.isBlank()) {
                    logger.warn("对话摘要为空，跳过: conversationId={}", conversationId);
                    return;
                }
                storeSummaryBlock(conversationId, nextBlockIndex, summary);
                updateSummaryMeta(conversationId, finalBlockEnd - 1, nextBlockIndex + 1);
                logger.info("短期记忆摘要块 {} 已创建: conversationId={}, 消息范围=[{},{})",
                        nextBlockIndex, conversationId, blockStart, finalBlockEnd);
                triggerLongTermExtractionIfNeeded(conversationId, userId);
            } catch (Exception e) {
                logger.error("短期记忆摘要生成失败: conversationId={}", conversationId, e);
            }
        });
    }

    // ==================== Tier 3: Long-term Memory ====================

    public String getLongTermContext(String userId) {
        if (!memoryConfig.getLongTerm().isEnabled()) {
            return "";
        }

        StringBuilder context = new StringBuilder();

        // Preferences (Hash)
        Map<Object, Object> prefs = redisTemplate.opsForHash().entries(ltmPrefsKey(userId));
        if (prefs != null && !prefs.isEmpty()) {
            context.append("### 用户偏好\n");
            prefs.forEach((k, v) -> context.append("- ").append(k).append(": ").append(v).append("\n"));
            context.append("\n");
        }

        // Facts (Sorted Set, by confidence desc)
        Set<String> facts = redisTemplate.opsForZSet()
                .reverseRange(ltmFactsKey(userId), 0, memoryConfig.getLongTerm().getMaxFacts() - 1);
        if (facts != null && !facts.isEmpty()) {
            context.append("### 重要事实\n");
            facts.forEach(f -> context.append("- ").append(f).append("\n"));
            context.append("\n");
        }

        // Topics (Sorted Set, by frequency desc)
        Set<String> topics = redisTemplate.opsForZSet()
                .reverseRange(ltmTopicsKey(userId), 0, memoryConfig.getLongTerm().getMaxTopics() - 1);
        if (topics != null && !topics.isEmpty()) {
            context.append("### 常讨论话题\n");
            topics.forEach(t -> context.append("- ").append(t).append("\n"));
        }

        return context.toString().trim();
    }

    public void updateUserPreference(String userId, String key, String value) {
        redisTemplate.opsForHash().put(ltmPrefsKey(userId), key, value);
    }

    public void upsertUserFact(String userId, String fact, double confidence) {
        if (confidence < memoryConfig.getLongTerm().getMinConfidence()) {
            return;
        }
        String key = ltmFactsKey(userId);
        redisTemplate.opsForZSet().add(key, fact, confidence);
        // Trim to max size
        Long size = redisTemplate.opsForZSet().size(key);
        if (size != null && size > memoryConfig.getLongTerm().getMaxFacts()) {
            long removeCount = size - memoryConfig.getLongTerm().getMaxFacts();
            redisTemplate.opsForZSet().removeRange(key, 0, removeCount - 1);
        }
    }

    public void incrementTopicFrequency(String userId, String topic) {
        String key = ltmTopicsKey(userId);
        redisTemplate.opsForZSet().incrementScore(key, topic, 1.0);
        Long size = redisTemplate.opsForZSet().size(key);
        if (size != null && size > memoryConfig.getLongTerm().getMaxTopics()) {
            long removeCount = size - memoryConfig.getLongTerm().getMaxTopics();
            redisTemplate.opsForZSet().removeRange(key, 0, removeCount - 1);
        }
    }

    public void triggerLongTermExtractionIfNeeded(String conversationId, String userId) {
        if (!memoryConfig.getLongTerm().isEnabled()) {
            return;
        }
        List<String> summaries = getSummaryBlocks(conversationId);
        if (summaries.isEmpty()) {
            return;
        }
        // Only extract from the latest summary block
        String latestSummary = summaries.get(summaries.size() - 1);

        memoryExecutor.execute(() -> {
            try {
                extractLongTermInfo(userId, latestSummary);
            } catch (Exception e) {
                logger.warn("长期记忆提取失败: userId={}", userId, e);
            }
        });
    }

    // ==================== Memory Context Builder ====================

    public MemoryContext buildMemoryContext(String conversationId, String userId) {
        // Tier 1: Working memory messages
        List<Map<String, Object>> rawWorking = getWorkingMemory(conversationId);
        List<Map<String, String>> workingMessages = rawWorking.stream()
                .map(this::normalizeMessage)
                .collect(Collectors.toList());

        // Tier 2: Summary blocks
        List<String> summaryBlocks = getSummaryBlocks(conversationId);
        int maxBlocks = memoryConfig.getSummary().getMaxTotalBlocks();
        if (summaryBlocks.size() > maxBlocks) {
            summaryBlocks = summaryBlocks.subList(summaryBlocks.size() - maxBlocks, summaryBlocks.size());
        }

        // Tier 3: Long-term context
        String longTermContext = getLongTermContext(userId);

        return new MemoryContext(workingMessages, summaryBlocks, longTermContext);
    }

    // ==================== Private Helpers ====================

    private List<Map<String, Object>> readWorkingMemoryRaw(String conversationId) {
        String key = "conversation:" + conversationId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            logger.error("解析工作记忆失败: conversationId={}", conversationId, e);
            return new ArrayList<>();
        }
    }

    private Map<String, String> normalizeMessage(Map<String, Object> raw) {
        Map<String, String> normalized = new HashMap<>();
        normalized.put("role", String.valueOf(raw.getOrDefault("role", "")));
        normalized.put("content", String.valueOf(raw.getOrDefault("content", "")));
        Object timestamp = raw.get("timestamp");
        if (timestamp != null) {
            normalized.put("timestamp", String.valueOf(timestamp));
        }
        return normalized;
    }

    private String generateConversationSummary(String userId,
                                                 List<Map<String, Object>> messageBlock) {
        try {
            return deepSeekClient.summarizeConversation(userId, messageBlock);
        } catch (Exception e) {
            logger.error("调用LLM生成对话摘要失败: userId={}", userId, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void extractLongTermInfo(String userId, String summaryText) {
        Map<String, Object> extracted = deepSeekClient.extractLongTermMemory(userId, summaryText);
        if (extracted.isEmpty()) {
            return;
        }

        // Process preferences
        List<Map<String, Object>> preferences = (List<Map<String, Object>>) extracted.get("preferences");
        if (preferences != null) {
            for (Map<String, Object> pref : preferences) {
                String key = String.valueOf(pref.getOrDefault("key", ""));
                String value = String.valueOf(pref.getOrDefault("value", ""));
                Object confObj = pref.get("confidence");
                double confidence = confObj instanceof Number n ? n.doubleValue() : 0.5;
                if (!key.isBlank() && !value.isBlank()
                        && confidence >= memoryConfig.getLongTerm().getMinConfidence()) {
                    updateUserPreference(userId, key, value);
                }
            }
        }

        // Process facts
        List<Map<String, Object>> facts = (List<Map<String, Object>>) extracted.get("facts");
        if (facts != null) {
            for (Map<String, Object> fact : facts) {
                String factText = String.valueOf(fact.getOrDefault("fact", ""));
                Object confObj = fact.get("confidence");
                double confidence = confObj instanceof Number n ? n.doubleValue() : 0.5;
                if (!factText.isBlank()) {
                    upsertUserFact(userId, factText, confidence);
                }
            }
        }

        // Process topics
        List<Map<String, Object>> topics = (List<Map<String, Object>>) extracted.get("topics");
        if (topics != null) {
            for (Map<String, Object> topic : topics) {
                String topicName = String.valueOf(topic.getOrDefault("topic", ""));
                if (!topicName.isBlank()) {
                    incrementTopicFrequency(userId, topicName);
                }
            }
        }

        // Update extraction metadata
        Map<String, String> meta = new HashMap<>();
        meta.put("lastExtractedAt", LocalDateTime.now().format(TIMESTAMP_FMT));
        String countStr = (String) redisTemplate.opsForHash().get(ltmMetaKey(userId), "extractionCount");
        int count = 0;
        if (countStr != null) {
            try { count = Integer.parseInt(countStr); } catch (NumberFormatException ignored) {}
        }
        meta.put("extractionCount", String.valueOf(count + 1));
        redisTemplate.opsForHash().putAll(ltmMetaKey(userId), meta);

        logger.info("长期记忆提取完成: userId={}, prefs={}, facts={}, topics={}",
                userId,
                preferences != null ? preferences.size() : 0,
                facts != null ? facts.size() : 0,
                topics != null ? topics.size() : 0);
    }

    // ==================== Redis Key Helpers ====================

    private String summaryBlockKey(String conversationId, int index) {
        return "mem:summary:" + conversationId + ":block:" + index;
    }

    private String summaryMetaKey(String conversationId) {
        return "mem:summary:meta:" + conversationId;
    }

    private String ltmPrefsKey(String userId) {
        return "mem:ltm:" + userId + ":prefs";
    }

    private String ltmFactsKey(String userId) {
        return "mem:ltm:" + userId + ":facts";
    }

    private String ltmTopicsKey(String userId) {
        return "mem:ltm:" + userId + ":topics";
    }

    private String ltmMetaKey(String userId) {
        return "mem:ltm:" + userId + ":meta";
    }
}
