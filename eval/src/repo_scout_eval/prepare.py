"""运行前置:health → 接入仓库 → 查索引状态 → 按配置触发索引 → 复查。

不修改后端参数、不清缓存、不删数据;重建索引默认关闭。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from .client import ApiCallError, RepoScoutClient
from .config import PrepareConfig
from .models import IndexStatusResponse

log = logging.getLogger("repo_scout_eval.prepare")


class PrepareError(Exception):
    """前置健康检查或仓库准备失败。"""


@dataclass(frozen=True)
class PreparedRepo:
    repo_id: int
    default_branch: str
    status: IndexStatusResponse
    indexed_now: bool
    """本次运行是否实际触发了索引。"""


def check_health(client: RepoScoutClient) -> None:
    try:
        result = client.health()
    except ApiCallError as exc:
        raise PrepareError(f"健康检查失败: {exc}") from exc
    if result.status != 200:
        raise PrepareError(f"健康检查返回 {result.status},服务不可用")
    status = result.payload.get("status")
    if status != "UP":
        raise PrepareError(f"健康检查 status={status},期望 UP")
    log.info("health ok latency=%sms", result.latency_ms)


def prepare_repo(client: RepoScoutClient, repo: str, config: PrepareConfig) -> PreparedRepo:
    """幂等接入 + 按需索引。返回 repoId 与最终索引状态。"""
    if not config.auto_register:
        raise PrepareError("auto_register=false 时需要在配置中改用已知 repoId,当前版本要求自动接入")
    try:
        registered = client.register_repo(repo)
    except ApiCallError as exc:
        raise PrepareError(f"接入仓库 {repo} 失败: {exc}") from exc
    repo_id = registered.id
    try:
        status = client.index_status(repo_id)
    except ApiCallError as exc:
        raise PrepareError(f"查询 repoId={repo_id} 索引状态失败: {exc}") from exc
    log.info(
        "repo ready repoId=%s branch=%s indexed=%s files=%s chunks=%s",
        repo_id,
        registered.defaultBranch,
        status.indexed,
        status.fileCount,
        status.chunkCount,
    )

    triggered = False
    if _should_index(status, config):
        log.info("触发同步索引 repoId=%s(allow_reindex=%s)", repo_id, config.allow_reindex)
        try:
            client.trigger_index(repo_id)
            status = client.index_status(repo_id)
        except ApiCallError as exc:
            raise PrepareError(f"索引 repoId={repo_id} 失败: {exc}") from exc
        triggered = True
        if not status.indexed:
            raise PrepareError(f"索引后复查仍为未索引: repoId={repo_id}")

    if config.require_index and not status.indexed:
        raise PrepareError(
            f"repoId={repo_id} 未建索引且未允许自动索引;请设置 prepare.auto_index=true "
            "或先手动调用 POST /api/repos/{id}/index"
        )
    return PreparedRepo(repo_id, registered.defaultBranch, status, triggered)


def _should_index(status: IndexStatusResponse, config: PrepareConfig) -> bool:
    if not config.auto_index:
        return False
    if not status.indexed:
        return True
    return config.allow_reindex
