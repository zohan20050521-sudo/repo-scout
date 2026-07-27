"""配置与凭据:env 覆盖、字段校验、密钥不出现在 repr / 序列化 / 日志。"""

from __future__ import annotations

import json
import logging

import pytest

from repo_scout_eval.config import (
    DEFAULT_BASE_URL,
    ENV_BASE_URL,
    ENV_INTERNAL_KEY,
    ENV_TIMEOUT,
    INTERNAL_KEY_HEADER,
    ConfigError,
    Credentials,
    RunConfig,
    TargetConfig,
    load_run_config,
)

SECRET = "super-secret-internal-key-value"


def test_credentials_default_when_env_absent() -> None:
    creds = Credentials.from_env({})
    assert creds.internal_key is None
    assert creds.headers() == {}
    assert creds.key_state() == "absent"


def test_credentials_read_key_and_timeout_from_env() -> None:
    creds = Credentials.from_env({ENV_INTERNAL_KEY: SECRET, ENV_TIMEOUT: "30"})
    assert creds.headers() == {INTERNAL_KEY_HEADER: SECRET}
    assert creds.timeout_s == 30.0
    assert creds.key_state() == "configured"


def test_blank_key_treated_as_absent() -> None:
    assert Credentials.from_env({ENV_INTERNAL_KEY: "   "}).internal_key is None


def test_secret_absent_from_repr_str_and_json() -> None:
    creds = Credentials.from_env({ENV_INTERNAL_KEY: SECRET})
    assert SECRET not in repr(creds)
    assert SECRET not in str(creds)
    assert SECRET not in json.dumps(creds.model_dump(mode="json"))


def test_secret_absent_from_logs(caplog: pytest.LogCaptureFixture) -> None:
    creds = Credentials.from_env({ENV_INTERNAL_KEY: SECRET})
    with caplog.at_level(logging.INFO):
        logging.getLogger("repo_scout_eval.test").info("key=%s", creds.key_state())
    assert SECRET not in caplog.text
    assert "configured" in caplog.text


def test_invalid_timeout_raises_config_error() -> None:
    with pytest.raises(ConfigError):
        Credentials.from_env({ENV_TIMEOUT: "abc"})
    with pytest.raises(ConfigError):
        Credentials.from_env({ENV_TIMEOUT: "-1"})


def test_target_base_url_precedence(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(ENV_BASE_URL, raising=False)
    assert TargetConfig().resolved_base_url() == DEFAULT_BASE_URL
    monkeypatch.setenv(ENV_BASE_URL, "http://env-host:9000/")
    assert TargetConfig().resolved_base_url() == "http://env-host:9000"
    assert TargetConfig(base_url="http://explicit:1/").resolved_base_url() == "http://explicit:1"


def test_run_config_rejects_duplicate_target_labels() -> None:
    with pytest.raises(ValueError, match="target label 必须唯一"):
        RunConfig(targets=[TargetConfig(label="a"), TargetConfig(label="a")])


def test_run_config_rejects_out_of_range_threshold() -> None:
    with pytest.raises(ValueError, match=r"\[0, 1\]"):
        RunConfig(thresholds=[0.5, 1.5])


def test_run_config_sorts_and_dedupes_thresholds() -> None:
    assert RunConfig(thresholds=[0.7, 0.5, 0.7]).thresholds == [0.5, 0.7]


def test_load_run_config_from_example(tmp_path: object) -> None:
    from pathlib import Path

    example = Path(__file__).resolve().parents[1] / "configs" / "local.example.yaml"
    config = load_run_config(example)
    assert config.prepare.allow_reindex is False
    assert config.concurrency == 1
    assert config.judge.enabled is False


def test_load_run_config_missing_file() -> None:
    with pytest.raises(ConfigError, match="不存在"):
        load_run_config("/nonexistent/path/config.yaml")


def test_load_run_config_rejects_unknown_field(tmp_path: object) -> None:
    from pathlib import Path

    path = Path(str(tmp_path)) / "bad.yaml"
    path.write_text("unknown_field: 1\n", encoding="utf-8")
    with pytest.raises(ConfigError, match="字段非法"):
        load_run_config(path)


def test_load_run_config_rejects_non_mapping(tmp_path: object) -> None:
    from pathlib import Path

    path = Path(str(tmp_path)) / "list.yaml"
    path.write_text("- 1\n- 2\n", encoding="utf-8")
    with pytest.raises(ConfigError, match="映射"):
        load_run_config(path)
