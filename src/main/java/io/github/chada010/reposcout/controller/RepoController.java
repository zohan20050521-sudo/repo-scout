package io.github.chada010.reposcout.controller;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.chada010.reposcout.controller.dto.IndexResponse;
import io.github.chada010.reposcout.controller.dto.RepoOnboardRequest;
import io.github.chada010.reposcout.controller.dto.RepoResponse;
import io.github.chada010.reposcout.rag.IndexingService;
import io.github.chada010.reposcout.service.RepoService;

/**
 * 仓库接入与查询接口(FR-2.1),v0.3 起兼向量化索引触发(FR-3.1)。契约见 docs/api.md:
 * 创建与重复接入均返回 200,重复接入幂等(同 id,刷新元信息)。
 */
@RestController
@RequestMapping("/api")
public class RepoController {

    private final RepoService repoService;
    private final IndexingService indexingService;

    public RepoController(RepoService repoService, IndexingService indexingService) {
        this.repoService = repoService;
        this.indexingService = indexingService;
    }

    @PostMapping("/repos")
    public RepoResponse onboard(@Valid @RequestBody RepoOnboardRequest request) {
        return RepoResponse.from(repoService.onboard(request.repo()));
    }

    @GetMapping("/repos")
    public List<RepoResponse> list() {
        return repoService.listRepos().stream()
                .map(RepoResponse::from)
                .toList();
    }

    @GetMapping("/repos/{id}")
    public RepoResponse get(@PathVariable long id) {
        return RepoResponse.from(repoService.getRepo(id));
    }

    /**
     * 触发向量化索引(FR-3.1):拉取该仓库文档、切分、进程内向量化并入库,重建幂等。
     * 仓库未接入 → 404 REPO_NOT_FOUND(IndexingService 校验);GitHub 不可用/限流 → 502。
     */
    @PostMapping("/repos/{id}/index")
    public IndexResponse index(@PathVariable long id) {
        return IndexResponse.of(id, indexingService.index(id));
    }
}
