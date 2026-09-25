package com.agentflow.runtime.event;

import java.util.Map;

/**
 * 一条事件消息（T7.1）——{@code id} 供确认（ACK），{@code payload} 是事件内容（JSON 序列化的 Map）。
 *
 * <p>id 由底层（Redis Streams XADD）生成：消费端处理成功后靠它 ACK，未 ACK 的消息留在 Pending 列表
 * 可重投（at-least-once，见 QA 70/72）。
 */
public record EventMessage(String id, Map<String, Object> payload) {
}
