package com.yizhaoqi.smartpai.service;

import java.util.List;
import java.util.Map;

/**
 * Structured memory context passed from MemoryService to LlmProviderRouter.
 * Replaces the flat {@code List<Map<String, String>>} history parameter.
 */
public record MemoryContext(
    /** Tier 1: Full-detail recent messages (not 800-char truncated) */
    List<Map<String, String>> workingMessages,

    /** Tier 2: Compressed summaries of older conversation blocks, oldest first */
    List<String> summaryBlocks,

    /** Tier 3: Extracted long-term facts, preferences, and frequent topics as text */
    String longTermContext
) {
    public static final MemoryContext EMPTY = new MemoryContext(List.of(), List.of(), "");
}
