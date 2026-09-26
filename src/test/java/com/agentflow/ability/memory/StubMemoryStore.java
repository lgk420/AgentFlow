package com.agentflow.ability.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentflow.ability.llm.dto.LlmChatMessage;

/**
 * 记忆测试替身（T10.1）——内存实现，按 sessionId 分桶，记录写入的 runId 供断言。
 * 非 bean，仅测试构造用（同 StubRetriever / StubReranker 套路）。
 */
public class StubMemoryStore implements MemoryStore {

    private final Map<String, List<LlmChatMessage>> sessions = new LinkedHashMap<>();
    private final List<String> appendedRunIds = new ArrayList<>();

    @Override
    public void append(String sessionId, String runId, List<LlmChatMessage> messages) {
        appendedRunIds.add(runId);
        List<LlmChatMessage> bucket = sessions.computeIfAbsent(sessionId, key -> new ArrayList<>());
        if (messages != null) {
            bucket.addAll(messages);
        }
    }

    @Override
    public List<LlmChatMessage> history(String sessionId, int limit) {
        List<LlmChatMessage> all = sessions.getOrDefault(sessionId, List.of());
        if (limit <= 0 || all.size() <= limit) {
            return List.copyOf(all);
        }
        return List.copyOf(all.subList(all.size() - limit, all.size()));
    }

    @Override
    public void clear(String sessionId) {
        sessions.remove(sessionId);
    }

    /** 断言用：最近一次 append 带的 runId。 */
    public List<String> getAppendedRunIds() {
        return List.copyOf(appendedRunIds);
    }
}
