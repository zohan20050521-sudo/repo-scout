"""repo-scout 黑盒评测工具。

只通过公开 REST API 观测:不读数据库、Redis、服务端日志或内部类,也不解析 Agent 工具轨迹
(当前无该 API)。全部指标为基于人工标注的 deterministic proxy,不等于事实正确率。
"""

__version__ = "0.4.0"

__all__ = ["__version__"]
