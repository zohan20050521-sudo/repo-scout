package io.github.chada010.reposcout.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import io.github.chada010.reposcout.entity.Repo;

/**
 * repo 表访问接口。owner/name 入库前已按 GitHub 规范大小写归一化,
 * 查重直接精确匹配即可。
 */
public interface RepoRepository extends JpaRepository<Repo, Long> {

    Optional<Repo> findByOwnerAndName(String owner, String name);

    List<Repo> findAllByOrderByIdDesc();
}
