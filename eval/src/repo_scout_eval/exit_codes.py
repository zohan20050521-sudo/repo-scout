"""退出码语义(README 同表)。"""

EXIT_OK = 0
"""全部成功。"""

EXIT_CONFIG_ERROR = 2
"""配置或数据集非法、文件缺失、参数冲突。"""

EXIT_PRECHECK_FAILED = 3
"""前置健康检查或仓库准备失败(含 401 fail-fast)。"""

EXIT_PARTIAL_FAILURE = 4
"""流程跑完,但存在失败 case。"""

EXIT_INTERNAL_ERROR = 5
"""程序内部错误(未预期异常)。"""

EXIT_INTERRUPTED = 130
"""用户中断(Ctrl-C);已完成的 case 仍保留在 cases.jsonl。"""
