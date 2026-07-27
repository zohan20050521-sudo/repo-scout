"""CLI 入口:参数解析、日志装配与退出码映射。业务逻辑在 commands.py。"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

from rich.console import Console
from rich.logging import RichHandler

from . import __version__
from .client import UnauthorizedError
from .commands import cmd_compare, cmd_run, cmd_summarize, cmd_validate
from .config import ConfigError
from .datasets import DatasetError
from .exit_codes import (
    EXIT_CONFIG_ERROR,
    EXIT_INTERNAL_ERROR,
    EXIT_INTERRUPTED,
    EXIT_OK,
    EXIT_PRECHECK_FAILED,
)
from .prepare import PrepareError
from .reports.writers import ResultsError

log = logging.getLogger("repo_scout_eval")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="repo-scout-eval",
        description="repo-scout 黑盒评测:只调用公开 REST API,凭据仅来自环境变量",
    )
    parser.add_argument("--version", action="version", version=f"repo-scout-eval {__version__}")
    parser.add_argument("-v", "--verbose", action="store_true", help="输出 DEBUG 日志(不含任何凭据)")
    sub = parser.add_subparsers(dest="command", required=True)

    validate = sub.add_parser("validate", help="离线校验数据集(不访问网络)")
    validate.add_argument("--dataset", default="datasets/v1.yaml")

    run = sub.add_parser("run", help="执行数据集并生成产物")
    run.add_argument("--config", default="configs/local.yaml")
    run.add_argument("--overwrite", action="store_true", help="允许覆盖同名结果目录")
    run.add_argument("--dataset", default=None, help="覆盖配置中的数据集路径")
    run.add_argument("--repetitions", type=int, default=None)
    run.add_argument("--concurrency", type=int, default=None)
    run.add_argument("--seed", type=int, default=None, help="固定 case 执行顺序(不保证模型输出确定)")
    run.add_argument("--only-case", action="append", dest="only_cases", default=None)
    run.add_argument("--only-category", action="append", dest="only_categories", default=None)
    run.add_argument("--fail-fast", action="store_true", default=None)

    summarize = sub.add_parser("summarize", help="从已有 cases.jsonl 重建汇总")
    summarize.add_argument("run_dir")
    summarize.add_argument("--dataset", default=None, help="覆盖 manifest 中记录的数据集路径")

    compare = sub.add_parser("compare", help="对比两个 run 的主要指标")
    compare.add_argument("run_a")
    compare.add_argument("run_b")
    return parser


def _configure_logging(verbose: bool) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(message)s",
        handlers=[RichHandler(rich_tracebacks=False, show_path=False, show_time=False)],
    )


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    _configure_logging(bool(args.verbose))
    console = Console()
    try:
        return _dispatch(args, console)
    except KeyboardInterrupt:
        console.print("[yellow]已中断;已完成的 case 保留在 cases.jsonl[/yellow]")
        return EXIT_INTERRUPTED
    except UnauthorizedError as exc:
        console.print(f"[red]{exc}[/red]")
        return EXIT_PRECHECK_FAILED
    except PrepareError as exc:
        console.print(f"[red]前置准备失败:{exc}[/red]")
        return EXIT_PRECHECK_FAILED
    except (ConfigError, DatasetError, ResultsError) as exc:
        console.print(f"[red]配置/数据错误:{exc}[/red]")
        return EXIT_CONFIG_ERROR
    except Exception as exc:
        log.exception("内部错误")
        console.print(f"[red]内部错误:{type(exc).__name__}: {exc}[/red]")
        return EXIT_INTERNAL_ERROR


def _dispatch(args: argparse.Namespace, console: Console) -> int:
    if args.command == "validate":
        return cmd_validate(console, Path(args.dataset))
    if args.command == "run":
        overrides = {
            "dataset": args.dataset,
            "repetitions": args.repetitions,
            "concurrency": args.concurrency,
            "seed": args.seed,
            "only_cases": args.only_cases,
            "only_categories": args.only_categories,
            "fail_fast": args.fail_fast,
        }
        exit_code, _ = cmd_run(console, Path(args.config), bool(args.overwrite), overrides)
        return exit_code
    if args.command == "summarize":
        dataset = Path(args.dataset) if args.dataset else None
        return cmd_summarize(console, Path(args.run_dir), dataset)
    if args.command == "compare":
        return cmd_compare(console, Path(args.run_a), Path(args.run_b))
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
