package io.github.chada010.reposcout.rag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import io.github.chada010.reposcout.config.RagProperties;
import io.github.chada010.reposcout.exception.GithubUnavailableException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.github.GithubApiClient;
import io.github.chada010.reposcout.github.RepoRef;

/**
 * 文档拉取(FR-3.1):从已接入仓库拉取待索引的文本文档。范围硬编码——
 * README + docs/ 目录下扩展名白名单文件。复用 {@link GithubApiClient}(不改其行为)。
 *
 * <p>失败策略:README 或单个 docs 文件拉取失败记 WARN 跳过(尽量索引其余);
 * 但 tree(目录列表)整体拉取失败不吞——异常上抛,由端点映射为 502。
 */
@Component
public class DocumentFetcher {

    private static final Logger log = LoggerFactory.getLogger(DocumentFetcher.class);

    private static final String RAW_ACCEPT = "application/vnd.github.raw+json";
    /** README raw 端点只回内容不回路径,统一以此标签入库(唯一键用)。 */
    private static final String README_LABEL = "README.md";
    private static final String DOCS_PREFIX = "docs/";
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of(".md", ".markdown", ".txt", ".adoc", ".rst");

    private final GithubApiClient client;
    private final RagProperties props;

    public DocumentFetcher(GithubApiClient client, RagProperties props) {
        this.client = client;
        this.props = props;
    }

    /** README 计入 maxFiles;README 在前,docs 按路径字典序补齐到上限。 */
    public List<FetchedDocument> fetch(RepoRef repo) {
        List<FetchedDocument> docs = new ArrayList<>();
        fetchReadme(repo).ifPresent(docs::add);
        int remaining = props.maxFiles() - docs.size();
        if (remaining > 0) {
            docs.addAll(fetchDocsDir(repo, remaining));
        }
        return docs;
    }

    private Optional<FetchedDocument> fetchReadme(RepoRef repo) {
        String path = "/repos/" + repo.owner() + "/" + repo.name() + "/readme";
        try {
            String raw = client.getRaw(path, RAW_ACCEPT);
            if (raw == null || raw.isBlank()) {
                log.info("仓库无 README,跳过: repo={}/{}", repo.owner(), repo.name());
                return Optional.empty();
            }
            int bytes = raw.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > props.maxFileBytes()) {
                log.info("README 超大小上限跳过: repo={}/{}, bytes={}, limit={}",
                        repo.owner(), repo.name(), bytes, props.maxFileBytes());
                return Optional.empty();
            }
            return Optional.of(new FetchedDocument(README_LABEL, raw));
        } catch (RepoNotFoundException | GithubUnavailableException e) {
            // GithubRateLimitException 是 GithubUnavailableException 子类,一并跳过(README 失败不致命)
            log.warn("README 拉取失败,跳过: repo={}/{}, error={}",
                    repo.owner(), repo.name(), e.getMessage());
            return Optional.empty();
        }
    }

    private List<FetchedDocument> fetchDocsDir(RepoRef repo, int budget) {
        // tree 整体失败不捕获:异常上抛由端点映射为 502
        JsonNode tree = client.getJson(treePath(repo), Map.of("recursive", "1"));
        List<String> paths = collectDocPaths(tree);
        if (paths.size() > budget) {
            log.info("docs 文件数超上限,按字典序截断: repo={}/{}, total={}, kept={}",
                    repo.owner(), repo.name(), paths.size(), budget);
            paths = paths.subList(0, budget);
        }
        List<FetchedDocument> docs = new ArrayList<>(paths.size());
        for (String path : paths) {
            fetchSingleDoc(repo, path).ifPresent(docs::add);
        }
        return docs;
    }

    /** 过滤 blob:docs/ 前缀 + 扩展名白名单 + 未超大小上限;按路径字典序排序。 */
    private List<String> collectDocPaths(JsonNode tree) {
        List<String> paths = new ArrayList<>();
        for (JsonNode node : tree.path("tree")) {
            if (!"blob".equals(node.path("type").asText())) {
                continue;
            }
            String path = node.path("path").asText();
            String lower = path.toLowerCase(Locale.ROOT);
            if (!lower.startsWith(DOCS_PREFIX) || !hasAllowedExtension(lower)) {
                continue;
            }
            long size = node.path("size").asLong(0);
            if (size > props.maxFileBytes()) {
                log.info("docs 文件超大小上限跳过: path={}, size={}, limit={}",
                        path, size, props.maxFileBytes());
                continue;
            }
            paths.add(path);
        }
        paths.sort(String::compareTo);
        return paths;
    }

    private Optional<FetchedDocument> fetchSingleDoc(RepoRef repo, String path) {
        try {
            String raw = client.getRaw(contentsPath(repo, path), RAW_ACCEPT);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new FetchedDocument(path, raw));
        } catch (RepoNotFoundException | GithubUnavailableException e) {
            log.warn("docs 文件拉取失败,跳过: repo={}/{}, path={}, error={}",
                    repo.owner(), repo.name(), path, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean hasAllowedExtension(String lowerPath) {
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lowerPath.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private String treePath(RepoRef repo) {
        String branch = UriUtils.encodePathSegment(repo.defaultBranch(), StandardCharsets.UTF_8);
        return "/repos/" + repo.owner() + "/" + repo.name() + "/git/trees/" + branch;
    }

    /** contents 端点路径:逐段编码文件路径但保留 '/' 结构,避免空格/特殊字符破坏请求。 */
    private String contentsPath(RepoRef repo, String filePath) {
        StringBuilder encoded = new StringBuilder();
        for (String segment : filePath.split("/")) {
            if (encoded.length() > 0) {
                encoded.append('/');
            }
            encoded.append(UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8));
        }
        return "/repos/" + repo.owner() + "/" + repo.name() + "/contents/" + encoded;
    }
}
