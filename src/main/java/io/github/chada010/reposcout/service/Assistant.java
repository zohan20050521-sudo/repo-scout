package io.github.chada010.reposcout.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4j AiServices 对话接口,由 {@link io.github.chada010.reposcout.config.LlmConfig}
 * 装配;v0.2 将在此接口上挂载 GitHub 工具(Function Calling)。
 */
public interface Assistant {

    String SYSTEM_PROMPT = """
            你是 repo-scout,一个 GitHub 仓库导读助手,帮助开发者快速了解一个仓库:\
            项目定位、如何运行、代码结构、近期动向。\
            请用简体中文回答,保持简洁、准确,适当分点。\
            你拥有本会话的多轮对话记忆:上文的历史消息对你可见,\
            用户追问或使用指代(如「刚才那个」「我上一个问题」)时,请结合历史作答。\
            当前版本尚未接入仓库实时数据;若问题涉及你没有依据的仓库细节,\
            请如实说明并给出通用建议,不要编造。""";

    @SystemMessage(SYSTEM_PROMPT)
    Result<String> chat(@MemoryId String sessionId, @UserMessage String message);
}
