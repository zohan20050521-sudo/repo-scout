package io.github.chada010.reposcout.rag;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.chada010.reposcout.config.RagProperties;
import io.github.chada010.reposcout.exception.GithubUnavailableException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.github.GithubApiClient;
import io.github.chada010.reposcout.github.RepoRef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * DocumentFetcher 单测:Mockito mock GithubApiClient,验证 docs/ 前缀 + 扩展名白名单过滤、
 * 文件数/大小上限、单文件失败跳过、tree 整体失败上抛。不打真实 GitHub。
 */
@ExtendWith(MockitoExtension.class)
class DocumentFetcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String README_PATH = "/repos/o/n/readme";
    private static final String TREE_PATH = "/repos/o/n/git/trees/main";

    @Mock
    private GithubApiClient client;

    private static RepoRef repo() {
        return new RepoRef("o", "n", "main");
    }

    private static RagProperties props(int maxFiles, int maxFileBytes) {
        return new RagProperties(maxFiles, maxFileBytes, 400, 80, 4, 0.5);
    }

    private DocumentFetcher fetcher(RagProperties props) {
        return new DocumentFetcher(client, props);
    }

    /** 构造 tree JSON;entries 每项为 {path, type, size}。 */
    private static JsonNode tree(Object[]... entries) {
        StringBuilder sb = new StringBuilder("{\"tree\":[");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"path\":\"").append(entries[i][0])
                    .append("\",\"type\":\"").append(entries[i][1])
                    .append("\",\"size\":").append(entries[i][2]).append('}');
        }
        sb.append("]}");
        try {
            return MAPPER.readTree(sb.toString());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object[] entry(String path, String type, int size) {
        return new Object[]{path, type, size};
    }

    private String contents(String path) {
        return "/repos/o/n/contents/" + path;
    }

    @Test
    void filtersToDocsPrefixAndExtensionWhitelistWithReadmeFirst() {
        given(client.getRaw(eq(README_PATH), anyString())).willReturn("# 项目\nREADME 正文");
        given(client.getJson(eq(TREE_PATH), anyMap())).willReturn(tree(
                entry("docs/a.md", "blob", 100),
                entry("docs/b.txt", "blob", 100),
                entry("docs/c.png", "blob", 100),      // 扩展名不在白名单
                entry("src/d.md", "blob", 100),          // 前缀不匹配
                entry("docs/sub/e.rst", "blob", 100),    // 深层仍算 docs/
                entry("docs", "tree", 0)                 // 目录本身跳过
        ));
        given(client.getRaw(eq(contents("docs/a.md")), anyString())).willReturn("a 内容");
        given(client.getRaw(eq(contents("docs/b.txt")), anyString())).willReturn("b 内容");
        given(client.getRaw(eq(contents("docs/sub/e.rst")), anyString())).willReturn("e 内容");

        List<FetchedDocument> docs = fetcher(props(30, 100000)).fetch(repo());

        assertThat(docs).extracting(FetchedDocument::filePath)
                .containsExactly("README.md", "docs/a.md", "docs/b.txt", "docs/sub/e.rst");
    }

    @Test
    void respectsMaxFilesIncludingReadmeAndTruncatesLexicographically() {
        given(client.getRaw(eq(README_PATH), anyString())).willReturn("README");
        given(client.getJson(eq(TREE_PATH), anyMap())).willReturn(tree(
                entry("docs/z.md", "blob", 10),
                entry("docs/a.md", "blob", 10),
                entry("docs/m.md", "blob", 10)
        ));
        // maxFiles=2:README 计 1,docs 预算 1 → 字典序取 docs/a.md
        lenient().when(client.getRaw(eq(contents("docs/a.md")), anyString())).thenReturn("a");
        lenient().when(client.getRaw(eq(contents("docs/m.md")), anyString())).thenReturn("m");
        lenient().when(client.getRaw(eq(contents("docs/z.md")), anyString())).thenReturn("z");

        List<FetchedDocument> docs = fetcher(props(2, 100000)).fetch(repo());

        assertThat(docs).extracting(FetchedDocument::filePath)
                .containsExactly("README.md", "docs/a.md");
    }

    @Test
    void skipsDocsExceedingByteSizeLimit() {
        given(client.getRaw(eq(README_PATH), anyString())).willReturn("README");
        given(client.getJson(eq(TREE_PATH), anyMap())).willReturn(tree(
                entry("docs/big.md", "blob", 999999),   // 超大小上限
                entry("docs/small.md", "blob", 100)
        ));
        given(client.getRaw(eq(contents("docs/small.md")), anyString())).willReturn("small");

        List<FetchedDocument> docs = fetcher(props(30, 100000)).fetch(repo());

        assertThat(docs).extracting(FetchedDocument::filePath)
                .containsExactly("README.md", "docs/small.md");
    }

    @Test
    void skipsSingleFileFetchFailureButKeepsOthers() {
        given(client.getRaw(eq(README_PATH), anyString())).willReturn("README");
        given(client.getJson(eq(TREE_PATH), anyMap())).willReturn(tree(
                entry("docs/a.md", "blob", 100),
                entry("docs/b.md", "blob", 100)
        ));
        given(client.getRaw(eq(contents("docs/a.md")), anyString()))
                .willThrow(new GithubUnavailableException("down"));
        given(client.getRaw(eq(contents("docs/b.md")), anyString())).willReturn("b 内容");

        List<FetchedDocument> docs = fetcher(props(30, 100000)).fetch(repo());

        assertThat(docs).extracting(FetchedDocument::filePath)
                .containsExactly("README.md", "docs/b.md");
    }

    @Test
    void missingReadmeStillFetchesDocs() {
        given(client.getRaw(eq(README_PATH), anyString()))
                .willThrow(new RepoNotFoundException("no readme"));
        given(client.getJson(eq(TREE_PATH), anyMap())).willReturn(tree(
                entry("docs/a.md", "blob", 100)
        ));
        given(client.getRaw(eq(contents("docs/a.md")), anyString())).willReturn("a 内容");

        List<FetchedDocument> docs = fetcher(props(30, 100000)).fetch(repo());

        assertThat(docs).extracting(FetchedDocument::filePath).containsExactly("docs/a.md");
    }

    @Test
    void treeFetchFailurePropagates() {
        given(client.getRaw(eq(README_PATH), anyString())).willReturn("README");
        given(client.getJson(eq(TREE_PATH), anyMap()))
                .willThrow(new GithubUnavailableException("tree down"));

        assertThatThrownBy(() -> fetcher(props(30, 100000)).fetch(repo()))
                .isInstanceOf(GithubUnavailableException.class);
    }
}
