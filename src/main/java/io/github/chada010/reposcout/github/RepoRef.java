package io.github.chada010.reposcout.github;

/**
 * 仓库标识:owner/name 为 GitHub 规范大小写(full_name 拆出),
 * defaultBranch 用于目录树等按分支取数的工具。v0.2 四个 GitHub 工具
 * 按仓库实例化时构造注入本记录,repoId 不进入模型可见参数。
 */
public record RepoRef(
        String owner,
        String name,
        String defaultBranch
) {
}
