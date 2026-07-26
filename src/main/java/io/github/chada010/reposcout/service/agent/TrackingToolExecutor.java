package io.github.chada010.reposcout.service.agent;

import java.util.concurrent.atomic.AtomicInteger;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具执行包装(FR-2.3):在真正执行前后记录调用轨迹,并对单次问答的工具调用轮数
 * 设上限。轮数计数器由 {@code RepoToolProvider} 按本次问答创建,四个工具的包装器共享,
 * 达到上限后不再执行真实工具,返回一行可读文本让模型基于已有信息收尾(不抛异常、不死循环)。
 *
 * <p>轨迹日志按 INFO 级记录 sessionId、工具名、参数摘要(截断 ≤200 字符)、耗时与结果长度;
 * <b>用户消息全文与工具完整返回不进 INFO</b>(工具参数仅含 maxDepth/state/limit 等,无敏感信息)。
 */
public class TrackingToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(TrackingToolExecutor.class);

    /** 参数摘要最大长度,防止异常长参数污染日志。 */
    private static final int ARGS_SUMMARY_MAX = 200;

    private final ToolExecutor delegate;
    private final String toolName;
    private final String sessionId;
    private final AtomicInteger roundCounter;
    private final int maxRounds;

    public TrackingToolExecutor(ToolExecutor delegate, String toolName, String sessionId,
                                AtomicInteger roundCounter, int maxRounds) {
        this.delegate = delegate;
        this.toolName = toolName;
        this.sessionId = sessionId;
        this.roundCounter = roundCounter;
        this.maxRounds = maxRounds;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        int round = roundCounter.incrementAndGet();
        if (round > maxRounds) {
            log.info("tool trajectory: sessionId={}, tool={}, round={}, action=capped(maxRounds={})",
                    sessionId, toolName, round, maxRounds);
            return "已达到工具调用轮数上限(" + maxRounds + "),请基于已获取的信息作答,暂无法继续调用工具。";
        }
        long start = System.currentTimeMillis();
        String result = delegate.execute(request, memoryId);
        long costMs = System.currentTimeMillis() - start;
        log.info("tool trajectory: sessionId={}, tool={}, round={}, args={}, costMs={}, resultLen={}",
                sessionId, toolName, round, summarizeArgs(request.arguments()), costMs,
                result == null ? 0 : result.length());
        return result;
    }

    /** 参数摘要:去除换行、截断到上限,避免多行/超长参数破坏单行日志。 */
    private String summarizeArgs(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        String oneLine = arguments.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= ARGS_SUMMARY_MAX) {
            return oneLine;
        }
        return oneLine.substring(0, ARGS_SUMMARY_MAX) + "…(截断)";
    }
}
