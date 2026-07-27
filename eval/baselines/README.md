# 基线数据摘要

> 数据集 v1 · 目标 commit `1f69151` · 本地环境 2026-07-27 · 运行参数见 manifest
>
> 所有数字都是单次 live run 的观测值，受服务端动态性影响，不代表长期稳定均值。
> 数值为 deterministic proxy，不等于事实正确率——完整指标定义见 [eval/README.md](../README.md)。

## 运行概要

| 项 | 值 |
| --- | --- |
| 数据集版本 | v1 |
| 目标仓库 | `zohan20050521-sudo/repo-scout` |
| 目标 commit | `1f69151`（v0.3.5） |
| 运行时间 | 2026-07-27T11:09Z |
| 总记录数 | 50（含 priming 轮） |
| 失败记录 | 0 |
| P50 / P95 延迟 | 5174 ms / 13916 ms |

## RAG 检索指标

| 指标 | 值 | 说明 |
| --- | --- | --- |
| citation_hit | **1.000** | 18 题均命中预期来源 |
| source_recall | 0.9444 | 预期路径平均命中比例 |
| citation_precision_proxy | 0.5833 | 基于标注路径的 proxy，非语义真值 |
| mrr | 0.6528 | 首个预期来源平均 reciprocal rank |
| mean_top_score | 0.7941 | 本轮最高 citation score 均值 |
| empty_citation_rate | 0.0278 | 仅 1 题无 citation |

## no-evidence 误召回（Issue #4 相关）

| 指标 | 值 |
| --- | --- |
| no_evidence case 数 | 7 |
| **false_positive_retrieval_rate** | **1.000** |
| mean_top_score | 0.7411 |
| top_scores 分布 | 0.670, 0.729, 0.734, 0.739, 0.769, 0.773, 0.773 |

所有 no-evidence 题（仓库外 / 无据问题）仍返回了 citation，topScore 最低 0.67，高于默认阈值 0.5。
这与 Issue #4 的预判一致（bge 余弦分数普遍偏高）。

## 回答指标

| 指标 | 值 |
| --- | --- |
| keyword_coverage_proxy | **1.000**（30 题全覆盖） |
| forbidden_claim_hit_rate | 0.2308（13 题中 3 题命中 forbidden） |
| empty_answer_rate | 0.000 |
| mean_answer_chars | 649.5 |

3 次 forbidden_claim_hit 均来自模型**如实说明了无据情况**但答案中包含了 forbidden 词语（如否定句式含原词）；
所有 no-evidence case 答案内容正确（回答了「不存在」），但 keywords 门控设计为 `answer_keywords=["未"]`，
涉及 `未` 字匹配到否定句，见 JSONL 原文。

## minScore 阈值曲线摘要（Issue #4）

| threshold | evidence_retained_hit | no_evidence_fp_rate | balanced_f1 |
| --- | --- | --- | --- |
| 0.50 | 1.000 | 1.000 | 0.000 |
| 0.55 | 1.000 | 1.000 | 0.000 |
| 0.60 | 1.000 | 1.000 | 0.000 |
| 0.65 | 1.000 | 1.000 | 0.000 |
| **0.70** | 1.000 | 0.857 | 0.250 |
| **0.75** | 0.824 | 0.429 | **0.675** ← F1 最高 |
| 0.80 | 0.353 | 0.000 | 0.522 |
| 0.85 | 0.000 | 0.000 | 0.000 |

**⚠ 左截断限制**：曲线只覆盖已通过服务端当前 `RAG_MIN_SCORE=0.5` 的候选，低于 0.5 的候选不可见。
候选区间 **0.65–0.72** 是在已返回 citations 上的参考建议，不作为通用最优阈值，
正式调参须另起 `RAG_MIN_SCORE=0.0` 专用实例获取完整曲线。

**本 PR 不修改默认 `RAG_MIN_SCORE=0.5`，不关闭 Issue #4。**

## Issue #3：历史摘录累积配对实验

| 指标 | 值 |
| --- | --- |
| 配对总数 | 4 |
| 可用配对（双侧成功且 B 一致） | 4 |
| mean Δ keyword_coverage | 0.000 |
| mean Δ forbidden_claim_hit | 0.000 |
| mean Δ citation_hit | 0.000 |
| mean Δ citation_count | 0.000 |
| mean Δ answer_chars | +23.25 字符 |
| mean Δ latency (ms) | — |

本次 4 组配对中未见关键词覆盖或禁止断言的显著退化。答案略有增长（+23 字符）可能是随机波动。
样本量小，结论不足以充分验证 Issue #3；需扩大 `--repetitions` 与 priming 多样性后再下判断。

**本 PR 只产出观测数据，不修改服务端 memory 行为，不关闭 Issue #3。**
