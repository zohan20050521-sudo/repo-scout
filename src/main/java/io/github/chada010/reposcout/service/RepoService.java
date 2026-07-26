package io.github.chada010.reposcout.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import io.github.chada010.reposcout.entity.Repo;
import io.github.chada010.reposcout.exception.RepoNotFoundException;
import io.github.chada010.reposcout.github.GithubApiClient;
import io.github.chada010.reposcout.repository.RepoRepository;

/**
 * 仓库接入服务(FR-2.1):调 GitHub 校验存在性并取元信息,幂等落库。
 * 大小写归一化以 GitHub 响应的 full_name 为准;不加类级事务,
 * 唯一键冲突兜底依赖「插入失败后重查更新」在独立事务中完成。
 */
@Service
public class RepoService {

    private static final Logger log = LoggerFactory.getLogger(RepoService.class);

    /** 与 repo.description 列宽一致,超长截断防止落库失败。 */
    private static final int DESCRIPTION_MAX_LENGTH = 1000;

    private final GithubApiClient githubApiClient;
    private final RepoRepository repoRepository;

    public RepoService(GithubApiClient githubApiClient, RepoRepository repoRepository) {
        this.githubApiClient = githubApiClient;
        this.repoRepository = repoRepository;
    }

    public Repo onboard(String repoInput) {
        RepoAddressParser.RepoAddress address = RepoAddressParser.parse(repoInput);
        JsonNode json = fetchRepoInfo(address);
        if (json.path("private").asBoolean(false)) {
            throw new RepoNotFoundException(
                    "私有仓库暂不支持,仅支持公开仓库:" + address.owner() + "/" + address.name());
        }
        String fullName = json.path("full_name").asText("");
        int slash = fullName.indexOf('/');
        String owner = slash > 0 ? fullName.substring(0, slash) : address.owner();
        String name = slash > 0 ? fullName.substring(slash + 1) : address.name();
        String defaultBranch = json.path("default_branch").asText("");
        String description = json.hasNonNull("description")
                ? truncate(json.get("description").asText())
                : null;
        String htmlUrl = json.path("html_url").asText("");
        return upsert(owner, name, defaultBranch, description, htmlUrl);
    }

    public List<Repo> listRepos() {
        return repoRepository.findAllByOrderByIdDesc();
    }

    public Repo getRepo(long id) {
        return repoRepository.findById(id)
                .orElseThrow(() -> new RepoNotFoundException("仓库未接入或不存在:id=" + id));
    }

    private JsonNode fetchRepoInfo(RepoAddressParser.RepoAddress address) {
        try {
            return githubApiClient.getJson("/repos/" + address.owner() + "/" + address.name(), Map.of());
        } catch (RepoNotFoundException e) {
            throw new RepoNotFoundException("GitHub 查无此公开仓库:" + address.owner() + "/" + address.name()
                    + "(私有仓库暂不支持)");
        }
    }

    private Repo upsert(String owner, String name, String defaultBranch, String description, String htmlUrl) {
        // DATETIME 无小数秒,截断到秒保证落库值与返回值一致
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        Optional<Repo> existing = repoRepository.findByOwnerAndName(owner, name);
        if (existing.isPresent()) {
            return refresh(existing.get(), defaultBranch, description, htmlUrl, now);
        }
        try {
            return repoRepository.save(new Repo(owner, name, defaultBranch, description, htmlUrl, now, now));
        } catch (DataIntegrityViolationException e) {
            // 并发接入同一仓库触发唯一键冲突:另一请求已插入,重查后走更新
            log.warn("repo 唯一键冲突,转为更新: {}/{}", owner, name);
            Repo concurrent = repoRepository.findByOwnerAndName(owner, name)
                    .orElseThrow(() -> e);
            return refresh(concurrent, defaultBranch, description, htmlUrl, now);
        }
    }

    private Repo refresh(Repo repo, String defaultBranch, String description, String htmlUrl, LocalDateTime now) {
        repo.refreshFrom(defaultBranch, description, htmlUrl, now);
        return repoRepository.save(repo);
    }

    private String truncate(String description) {
        return description.length() <= DESCRIPTION_MAX_LENGTH
                ? description
                : description.substring(0, DESCRIPTION_MAX_LENGTH);
    }
}
