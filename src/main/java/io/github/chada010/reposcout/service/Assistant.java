package io.github.chada010.reposcout.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4j AiServices 对话接口,由 {@link io.github.chada010.reposcout.config.LlmConfig}
 * 装配。v0.2 起系统提示词按会话是否绑定仓库动态切换,因此<b>不再用 {@code @SystemMessage}
 * 注解</b>(注解优先级高于 provider,会屏蔽动态切换),改由 {@code systemMessageProvider}
 * 在装配处按绑定状态选择 {@link #UNBOUND_SYSTEM_PROMPT} 或 {@link #BOUND_SYSTEM_PROMPT};
 * 工具集则由 {@code RepoToolProvider} 按绑定状态动态挂载。
 */
public interface Assistant {

    /** 绑定与未绑定共用的人设与记忆说明部分。 */
    String SYSTEM_PROMPT_PREFIX = """
            你是 repo-scout,一个 GitHub 仓库导读助手,帮助开发者快速了解一个仓库:\
            项目定位、如何运行、代码结构、近期动向。\
            请用简体中文回答,保持简洁、准确,适当分点。\
            你拥有本会话的多轮对话记忆:上文的历史消息对你可见,\
            用户追问或使用指代(如「刚才那个」「我上一个问题」)时,请结合历史作答。""";

    /** 未绑定仓库的会话:纯对话,不挂工具,无仓库实时数据。 */
    String UNBOUND_SYSTEM_PROMPT = SYSTEM_PROMPT_PREFIX
            + "当前版本尚未接入仓库实时数据;若问题涉及你没有依据的仓库细节,"
            + "请如实说明并给出通用建议,不要编造。";

    /** 已绑定仓库的会话:文档摘录注入(检索)与工具调用(实时数据)双引擎并用。 */
    String BOUND_SYSTEM_PROMPT = SYSTEM_PROMPT_PREFIX
            + "部分用户消息后会附带从该仓库文档中检索到的「文档摘录」(带来源文件路径):"
            + "回答时优先依据摘录作答,并在答案中注明所引用摘录的来源文件路径;"
            + "摘录与问题无关时忽略即可。"
            + "你还可以调用工具获取该仓库的实时数据(目录树、README、issues、最近提交),"
            + "需要实时或结构化数据时用工具取数。"
            + "摘录与工具都取不到依据时,如实说明「仓库文档与数据中未找到」,不要编造。";

    Result<String> chat(@MemoryId String sessionId, @UserMessage String message);
}
