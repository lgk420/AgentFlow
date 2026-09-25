package com.agentflow.runtime.event;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 事件 record ↔ payload Map 转换（T7.2）——record 经 Jackson 序列化成 Map（含 type 判别字段），
 * 供 {@link EventBus#publish}；消费端（T7.3 worker）按 type 反序列化回对应 record。
 */
@Component
public class EventCodec {

    private final ObjectMapper mapper;

    public EventCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 事件 record → payload Map（含 {@link Events#TYPE_FIELD} 判别字段）。
     */
    public Map<String, Object> toPayload(Object event) {
        return mapper.convertValue(event, Map.class);
    }
}
