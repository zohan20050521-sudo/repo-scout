package io.github.chada010.reposcout.service.agent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.chada010.reposcout.config.AgentProperties;
import io.github.chada010.reposcout.config.ToolsProperties;
import io.github.chada010.reposcout.entity.Repo;
import io.github.chada010.reposcout.github.GithubApiClient;
import io.github.chada010.reposcout.github.RepoRef;
import io.github.chada010.reposcout.memory.SessionRepoBinding;
import io.github.chada010.reposcout.repository.RepoRepository;
import io.github.chada010.reposcout.tools.GithubCommitsTool;
import io.github.chada010.reposcout.tools.GithubIssuesTool;
import io.github.chada010.reposcout.tools.GithubReadmeTool;
import io.github.chada010.reposcout.tools.GithubTreeTool;

/**
 * 按会话动态挂载 GitHub 工具(FR-2.3 核心)。单例 {@code AiServices} 通过本 provider
 * 为每次问答决定工具集:
 * <ul>
 *   <li>未绑定仓库的会话 → 返回空工具集,退化为 v0.1 纯对话;</li>
 *   <li>已绑定 → 查 repo 记录,构造 {@link RepoRef},实例化四个工具并挂载。</li>
 * </ul>
 *
 * <p>每次 {@code provideTools}(非 dynamic,单次问答只调用一次)新建一个共享轮数计数器,
 * 四个工具的执行都经 {@link TrackingToolExecutor} 包装,统一记录调用轨迹并施加轮数上限。
 * repoId 由服务端从绑定关系解析,不进入模型可见的工具参数。
 */
@Component
public class RepoToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(RepoToolProvider.class);

    private final GithubApiClient githubApiClient;
    private final ToolsProperties toolsProperties;
    private final AgentProperties agentProperties;
    private final SessionRepoBinding sessionRepoBinding;
    private final RepoRepository repoRepository;

    public RepoToolProvider(GithubApiClient githubApiClient, ToolsProperties toolsProperties,
                            AgentProperties agentProperties, SessionRepoBinding sessionRepoBinding,
                            RepoRepository repoRepository) {
        this.githubApiClient = githubApiClient;
        this.toolsProperties = toolsProperties;
        this.agentProperties = agentProperties;
        this.sessionRepoBinding = sessionRepoBinding;
        this.repoRepository = repoRepository;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        String sessionId = String.valueOf(request.chatMemoryId());
        Optional<Long> boundRepoId = sessionRepoBinding.get(sessionId);
        if (boundRepoId.isEmpty()) {
            return null; // 未绑定:无工具,纯对话
        }
        Optional<Repo> repo = repoRepository.findById(boundRepoId.get());
        if (repo.isEmpty()) {
            // 绑定指向的仓库记录已不存在(极少见:接入记录被清理),降级为纯对话
            log.warn("会话绑定的仓库记录不存在,本轮不挂工具: sessionId={}, repoId={}", sessionId, boundRepoId.get());
            return null;
        }
        RepoRef repoRef = toRepoRef(repo.get());
        List<Object> tools = List.of(
                new GithubTreeTool(githubApiClient, toolsProperties, repoRef),
                new GithubReadmeTool(githubApiClient, toolsProperties, repoRef),
                new GithubIssuesTool(githubApiClient, toolsProperties, repoRef),
                new GithubCommitsTool(githubApiClient, toolsProperties, repoRef));

        AtomicInteger roundCounter = new AtomicInteger(0);
        int maxRounds = agentProperties.maxToolRounds();
        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        for (Object tool : tools) {
            addTool(builder, tool, sessionId, roundCounter, maxRounds);
        }
        return builder.build();
    }

    private RepoRef toRepoRef(Repo repo) {
        return new RepoRef(repo.getOwner(), repo.getName(), repo.getDefaultBranch());
    }

    /** 反射取工具对象上的 @Tool 方法,构造规格与执行器,并用轨迹/计数包装器包裹。 */
    private void addTool(ToolProviderResult.Builder builder, Object tool, String sessionId,
                         AtomicInteger roundCounter, int maxRounds) {
        for (Method method : tool.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
            ToolExecutor delegate = new DefaultToolExecutor(tool, method);
            ToolExecutor tracking = new TrackingToolExecutor(delegate, spec.name(), sessionId, roundCounter, maxRounds);
            builder.add(spec, tracking);
        }
    }
}
