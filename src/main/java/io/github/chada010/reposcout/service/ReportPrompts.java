package io.github.chada010.reposcout.service;

import java.util.List;

import io.github.chada010.reposcout.entity.Repo;

/**
 * 仓库导读报告的提示词与结构契约(FR-3.3):单条消息模板、五节标题(字节精确、
 * 顺序固定)、结构校验与纠正指令。从 {@link ReportService} 拆出以控制文件行数,
 * 让编排逻辑与长文本模板分离。
 */
final class ReportPrompts {

    /** 报告必须且只能包含的五个二级标题,顺序固定,字节精确。 */
    static final List<String> SECTION_TITLES = List.of(
            "## 项目定位",
            "## 技术栈",
            "## 目录结构解读",
            "## 上手指引",
            "## 近期动向");

    /** 首次输出结构不合规时追加的纠正指令(重试一次)。 */
    static final String CORRECTION_INSTRUCTION = """
            你上一次的输出不符合结构要求。请重新输出完整报告,严格满足:
            只包含以下五个二级标题,顺序固定、逐字一致,且各节正文非空:
            ## 项目定位
            ## 技术栈
            ## 目录结构解读
            ## 上手指引
            ## 近期动向
            除报告本身外不要输出任何其他内容。""";

    private ReportPrompts() {
    }

    /** 组装单条用户消息:仓库元信息 + 四块工具数据 + 文档摘录区 + 输出要求。 */
    static String buildPrompt(Repo repo, String tree, String readme, String issues,
                              String commits, String excerpts) {
        return """
                请依据下面给定的仓库数据,生成一份该 GitHub 仓库的导读报告。

                【仓库元信息】
                owner: %s
                name: %s
                默认分支: %s
                描述: %s

                【目录树】
                %s

                【README】
                %s

                【开放 issues】
                %s

                【最近提交】
                %s

                【文档摘录】(从该仓库已索引文档中按固定问题检索到的相关片段,带来源文件路径)
                %s

                输出要求:
                1. 输出 Markdown,只包含以下五个二级标题,顺序固定、逐字一致:## 项目定位、## 技术栈、## 目录结构解读、## 上手指引、## 近期动向;
                2. 各节内容非空;只依据上面给定的数据作答,某块数据缺失或降级(如提示限流、不可用、未索引)时在对应节如实说明,不编造;
                3. 「上手指引」优先依据 README 与文档摘录作答,并注明所依据的来源文件路径;
                4. 「近期动向」依据最近提交与开放 issues 归纳。
                除报告本身外不要输出任何其他内容。""".formatted(
                repo.getOwner(), repo.getName(), repo.getDefaultBranch(),
                repo.getDescription() == null ? "(无)" : repo.getDescription(),
                tree, readme, issues, commits, excerpts);
    }

    /** 结构校验:五个标题按序齐全,且每节正文非空白(取到下一标题或全文末尾)。 */
    static boolean isStructureValid(String report) {
        if (report == null) {
            return false;
        }
        int[] starts = new int[SECTION_TITLES.size()];
        int from = 0;
        for (int i = 0; i < SECTION_TITLES.size(); i++) {
            starts[i] = report.indexOf(SECTION_TITLES.get(i), from);
            if (starts[i] < 0) {
                return false;
            }
            from = starts[i] + SECTION_TITLES.get(i).length();
        }
        for (int i = 0; i < SECTION_TITLES.size(); i++) {
            int bodyStart = starts[i] + SECTION_TITLES.get(i).length();
            int bodyEnd = i + 1 < starts.length ? starts[i + 1] : report.length();
            if (report.substring(bodyStart, bodyEnd).isBlank()) {
                return false;
            }
        }
        return true;
    }
}
