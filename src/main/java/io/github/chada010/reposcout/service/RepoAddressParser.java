package io.github.chada010.reposcout.service;

import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

import io.github.chada010.reposcout.exception.InvalidParamException;

/**
 * 仓库地址解析(FR-2.1):接受裸 owner/repo 与 https://github.com/owner/repo
 * (URL 形态允许尾部 / 或 .git),其余形态一律判非法。
 * 解析结果为用户输入的原始大小写,规范化以 GitHub API 返回的 full_name 为准。
 */
public final class RepoAddressParser {

    private static final String GITHUB_URL_PREFIX = "https://github.com/";
    private static final Pattern OWNER_PATTERN = Pattern.compile("[A-Za-z0-9-]+");
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    /** "." / ".." 虽满足字符集,但会被当作 URL 路径段解析,直接拒绝。 */
    private static final Set<String> FORBIDDEN_NAMES = Set.of(".", "..");

    private static final String FORMAT_MESSAGE =
            "repo 格式不合法:仅支持 owner/repo 或 https://github.com/owner/repo(URL 允许尾部 / 或 .git)";

    private RepoAddressParser() {
    }

    /** 解析出的 owner 与仓库名(未做大小写归一化)。 */
    public record RepoAddress(String owner, String name) {
    }

    public static RepoAddress parse(String input) {
        if (!StringUtils.hasText(input)) {
            throw new InvalidParamException("repo 不能为空");
        }
        String value = input.trim();
        if (value.startsWith(GITHUB_URL_PREFIX)) {
            value = stripUrlSuffix(value.substring(GITHUB_URL_PREFIX.length()));
        } else if (value.contains(":") || value.contains("//") || value.startsWith("git@")) {
            // http://、git@、非 github.com 域名等形态统一拒绝
            throw new InvalidParamException(FORMAT_MESSAGE);
        }
        String[] parts = value.split("/", -1);
        if (parts.length != 2
                || !OWNER_PATTERN.matcher(parts[0]).matches()
                || !NAME_PATTERN.matcher(parts[1]).matches()
                || FORBIDDEN_NAMES.contains(parts[1])) {
            throw new InvalidParamException(FORMAT_MESSAGE);
        }
        return new RepoAddress(parts[0], parts[1]);
    }

    private static String stripUrlSuffix(String path) {
        String result = path;
        if (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.endsWith(".git")) {
            result = result.substring(0, result.length() - ".git".length());
        }
        return result;
    }
}
