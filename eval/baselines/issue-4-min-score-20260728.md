# Issue #4 RAG_MIN_SCORE 调参证据

> 本文只记录当前 main（`3ce9fc6dd394150558cf07d03398442f11d9d721`）的本地黑盒观测。
> 指标是 deterministic proxy，不是事实正确率或语义相关性真值。

## 运行清单

| 项 | 值 |
| --- | --- |
| 目标仓库 | `zohan20050521-sudo/repo-scout` |
| 评测 artifact commit | `3ce9fc6dd394150558cf07d03398442f11d9d721` |
| run id | `20260727T183049Z-local` |
| target | `http://127.0.0.1:18050`（本机隔离评测实例） |
| 评测实例 | `RAG_MIN_SCORE=0.0`；独立 MySQL/Redis；未触碰现有实例或线上服务 |
| 数据集 | v1；run 时 SHA-256 `d42a7a4ec40bca7d8d9e9f206d72ab72fb81b8b30fffa30ad79b6023254f07cd` |
| 同步后数据集 SHA-256 | `8eb0773d07aad9f6a060b29a22f3de39c05b764485f6694877616e34e721b90e` |
| repetitions / concurrency / pause | `2` / `1` / `1.0s` |
| judge | disabled |
| 结果目录 | `eval/results/20260727T183049Z-local/`（原始结果未提交） |
| 完整性 | v1 全部 33 case；100 条记录（含 priming）；失败 0；重试 0 |
| 索引 | `indexed=true`，4 files，174 chunks |
| 凭据状态 | `DEEPSEEK_API_KEY=configured`（仅复用本机既有进程环境）；`GITHUB_TOKEN=absent`；评测 internal key=absent |

第一次尝试的前置健康检查因 Java 进程被执行环境回收而退出码为 3，结果目录
`eval/results/20260727T182927Z-local/manifest.json` 保留为失败记录；未将其混入成功 run。

同步默认值预期后的重跑 `20260727T185536Z-local` 也如实保留：健康检查为 200，
但匿名 GitHub API 在 `POST /api/repos` 连续 3 次返回 `502 GITHUB_UNAVAILABLE`，runner
退出码为 3，未产生 case 记录。该环境阻塞不被冒充为成功 full run；成功 run 的 source paths
与 citation scores 不受默认值预期文字同步影响。

## 门槛与曲线

产品门槛只统计 `rag_fact` + `rag_multi_source` 且有 `source_paths` 的 evidence，及
`no_evidence`。每个 repetition 为 14 个 evidence、6 个 no-evidence；两次重放数值完全一致。

| threshold | evidence_retained_hit | expected_source_recall_proxy | no_evidence FPR | balanced_f1 | empty_retrieval_rate |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 0.50（基线） | 1.0000 | 0.8929 | 1.0000 | 0.0000 | 0.0000 |
| 0.55 | 1.0000 | 0.8929 | 1.0000 | 0.0000 | 0.0000 |
| 0.60 | 1.0000 | 0.8929 | 1.0000 | 0.0000 | 0.0000 |
| 0.65 | 1.0000 | 0.8929 | 1.0000 | 0.0000 | 0.0000 |
| 0.70 | 1.0000 | 0.8929 | 0.8333 | 0.2857 | 0.0500 |
| **0.75** | **0.9286** | **0.8214** | **0.3333** | **0.7761** | 0.2000 |
| 0.80 | 0.4286 | 0.3929 | 0.0000 | 0.6000 | 0.6500 |
| 0.85 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.9500 |

`0.75` 同时满足 evidence hit ≥ 0.80、source recall ≥ 0.75、no-evidence FPR ≤ 0.50，且
相对 `0.50` 的 FPR 从 1.0000 降至 0.3333；在满足前两项的候选中 balanced F1 最高。
`0.80` 虽然清除 no-evidence citation，却牺牲大部分有据问题（hit/recall 均低于门槛）。
因此推荐 `RAG_MIN_SCORE=0.75`，两次 repetition 无推荐分歧。

工具生成的完整阈值曲线仍保留 `0.50 … 0.85` 候选集；run 观测到的全量最低 citation
score 为 `0.6552`。曲线受服务端候选截断边界限制，即使本次实例设为 `0.0`，也不能声称
证明观测范围以下的完整分布。

## 默认值与 Issue 状态

已将 `src/main/resources/application.yml`、README、设计文档、部署模板和评测数据集中的
当前默认值同步为 `0.75`，并增加配置绑定测试验证缺省值与显式 `RAG_MIN_SCORE` 覆盖。
成功 full run 使用同步前数据集 hash（仅默认值 expected keyword/说明随后同步）；同步后 hash
已记录在上表，因 GitHub 匿名限流未能再次完成全量 case。该限制不改变本次阈值重放的
source/citation 证据。本 baseline 对应的 PR 应使用 `Closes #4`；Issue #4 在该证据下完成调参并关闭。

## 其他指标与限制

- runner 汇总（含 pollution fresh 记录）`citation_hit=0.9444`、`source_recall=0.8611`、`empty_citation_rate=0.0278`；no-evidence 原始运行时 FPR 为 `1.0000`，离线重放后按上表下降。
- 成功率 `1.0`（80/80 scored attempts），P50/P95 延迟 `4717/14772 ms`；未启用 judge。
- 部分 `tool_live` 回答遇到匿名 GitHub API 限流，但 HTTP 请求成功，失败/重试 case 为 0；这不改变 RAG citation 重放数据。
- 所有指标都是 deterministic proxy，样本只有两次 repetition；模型输出、GitHub 限流和数据集标注均可能影响结果。
- 评测只使用公开 REST 端点，未读取数据库、Redis、服务端日志或 Agent 工具轨迹；结论仅适用于该 commit、数据集和本地评测环境，不代表通用真值或线上结果。
