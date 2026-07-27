# repo-scout 评测汇总 `20260727T000000Z-sample`

## 运行清单

| 项 | 值 |
| --- | --- |
| tool_version | 0.4.0 |
| schema_version | 1 |
| target | `local` @ `http://localhost:8080` |
| min_score_label | 0.5(未由服务端证明,仅操作者声明) |
| repo | `zohan20050521-sudo/repo-scout` (repoId=4, head=unknown) |
| dataset | `/home/chad/.treehouse/repo-scout-8e662e/3/repo-scout/eval/datasets/v1.yaml` v1 sha256=aaaaaaaaaaaa… |
| index | indexed=True files=4 chunks=163 |
| window | 2026-07-27T00:00:00Z → 2026-07-27T00:05:00Z |
| python | 3.12.3 |
| judge | disabled |

## 总体指标

| 指标 | 值 | 备注 |
| --- | --- | --- |
| 成功率 | 1.0 | 2/2 |
| latency P50 / P95 (ms) | 12394.0 / 12394.0 | mean=7333.0 |
| citation_hit | 1.0 | n=1 |
| source_recall | 1.0 | 命中预期路径占比均值 |
| citation_precision_proxy | 0.25 | 基于标注路径的 proxy |
| mrr | 0.5 | 首个预期来源 reciprocal rank |
| mean_top_score | 0.8645 | empty_citation_rate=0.0 |
| keyword_coverage_proxy | 1.0 | n=2 |
| forbidden_claim_hit_rate | 1.0 | n=1 |
| no_evidence 误召回率 | 1.0 | mean_top_score=0.7734 |
| composite(透明加权) | 0.8 | weights={'citation_hit': 0.3, 'keyword_coverage': 0.4, 'no_evidence_clean': 0.2, 'success_rate': 0.1} |

> keyword_coverage / citation_precision_proxy 等均为基于人工标注的 deterministic proxy,不等于事实正确率或语义相关性真值。

## 分类别指标

| category | n | 成功率 | citation_hit | keyword_cov | forbidden_hit | P50 ms | P95 ms |
| --- | --- | --- | --- | --- | --- | --- | --- |
| no_evidence | 1 | 1.0 | 0.0 | 1.0 | 1.0 | 12394.0 | 12394.0 |
| rag_fact | 1 | 1.0 | 1.0 | 1.0 | 0.0 | 2272.0 | 2272.0 |

## minScore 阈值重放(Issue #4)

| threshold | evidence_retained_hit | expected_source_recall_proxy | no_evidence_fp_rate | avg_retained_citations | empty_retrieval_rate | balanced_f1 |
| --- | --- | --- | --- | --- | --- | --- |
| 0.50 | 1.000 | 1.000 | 1.000 | 4.00 | 0.000 | 0.000 |
| 0.55 | 1.000 | 1.000 | 1.000 | 4.00 | 0.000 | 0.000 |
| 0.60 | 1.000 | 1.000 | 1.000 | 4.00 | 0.000 | 0.000 |
| 0.65 | 1.000 | 1.000 | 1.000 | 4.00 | 0.000 | 0.000 |
| 0.70 | 1.000 | 1.000 | 1.000 | 4.00 | 0.000 | 0.000 |
| 0.75 | 1.000 | 1.000 | 1.000 | 4.00 | 0.000 | 0.000 |
| 0.80 | 0.000 | 0.000 | 0.000 | 0.50 | 0.500 | 0.000 |
| 0.85 | 0.000 | 0.000 | 0.000 | 0.50 | 0.500 | 0.000 |

> 阈值曲线为左截断:后端已按运行时 RAG_MIN_SCORE 过滤候选,低于该阈值的候选不可见,本曲线只反映在已返回 citations 上继续提高阈值的影响。
>
> 本次 run 观测到的最低 citation score = 0.7620。

本次数据下 balanced_f1 最高点为 threshold=0.50(evidence_retained_hit=1.00,no_evidence_false_positive_rate=1.00);该结论仅限本数据集与本次 run,且受左截断限制,不作为通用最优阈值。

本 PR 不修改后端默认 `RAG_MIN_SCORE=0.5`,不关闭 Issue #4。

## 失败 case

无。

## 逐题摘要

| case_id | category | variant | 状态 | cit_hit | kw_cov | ms | answer 摘要 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `rag-fact-error-codes` | rag_fact | single | ok | 1 | 1.00 | 2272 | 根据仓库文档 **docs/api.md** 中的记录，该项目的统一错误码如下： | 错误码名称 | HTTP 状态码 | | --- | --- | | `INVALID_PAR…(共 145 字符,原文见 cases.jsonl) |
| `no-evidence-distributed-tx` | no_evidence | single | ok | 0 | 1.00 | 12394 | ## 回答 **这个项目没有使用 Seata 或 TCC 实现分布式事务，因为 repo-scout 本身不涉及分布式事务场景。** 原因如下： 1. **项目定位**：repo-…(共 143 字符,原文见 cases.jsonl) |

完整 answer 与 citations 见 `cases.jsonl`。
