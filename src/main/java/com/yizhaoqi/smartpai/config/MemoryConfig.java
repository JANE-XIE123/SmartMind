package com.yizhaoqi.smartpai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "memory")
public class MemoryConfig {

    private WorkingMemory working = new WorkingMemory();
    private Summary summary = new Summary();
    private LongTerm longTerm = new LongTerm();

    public WorkingMemory getWorking() { return working; }
    public void setWorking(WorkingMemory working) { this.working = working; }

    public Summary getSummary() { return summary; }
    public void setSummary(Summary summary) { this.summary = summary; }

    public LongTerm getLongTerm() { return longTerm; }
    public void setLongTerm(LongTerm longTerm) { this.longTerm = longTerm; }

    public static class WorkingMemory {
        private int maxTurns = 10;
        private int maxContentChars = 2000;

        public int getMaxTurns() { return maxTurns; }
        public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }

        public int getMaxContentChars() { return maxContentChars; }
        public void setMaxContentChars(int maxContentChars) { this.maxContentChars = maxContentChars; }
    }

    public static class Summary {
        private int blockSizeTurns = 10;
        private int maxTotalBlocks = 5;
        private double temperature = 0.2;

        public int getBlockSizeTurns() { return blockSizeTurns; }
        public void setBlockSizeTurns(int blockSizeTurns) { this.blockSizeTurns = blockSizeTurns; }

        public int getMaxTotalBlocks() { return maxTotalBlocks; }
        public void setMaxTotalBlocks(int maxTotalBlocks) { this.maxTotalBlocks = maxTotalBlocks; }

        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
    }

    public static class LongTerm {
        private boolean enabled = true;
        private int maxFacts = 20;
        private int maxTopics = 15;
        private double minConfidence = 0.5;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getMaxFacts() { return maxFacts; }
        public void setMaxFacts(int maxFacts) { this.maxFacts = maxFacts; }

        public int getMaxTopics() { return maxTopics; }
        public void setMaxTopics(int maxTopics) { this.maxTopics = maxTopics; }

        public double getMinConfidence() { return minConfidence; }
        public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
    }
}
