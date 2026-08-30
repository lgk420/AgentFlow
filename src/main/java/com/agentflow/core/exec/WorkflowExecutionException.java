package com.agentflow.core.exec;

/**
 * 工作流执行期异常：并行派发基础设施错误等。
 *
 * <p>节点自身的执行失败不走这里——由 {@link WorkflowExecutor} 逐节点捕获并记入 NodeOutput FAILED；
 * 本异常只在并行调度层出现不可预期故障时抛出。
 */
public class WorkflowExecutionException extends RuntimeException {

    public WorkflowExecutionException(String message) {
        super(message);
    }

    public WorkflowExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
