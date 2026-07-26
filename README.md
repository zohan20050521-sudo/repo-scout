# repo-scout

> GitHub 仓库导读 Agent:输入一个仓库地址,即可用自然语言提问「怎么运行、架构如何、最近在修什么 bug」,由 Agent 结合工具调用与 RAG 给出答案。

## 核心功能

- **仓库接入**:输入 GitHub 仓库地址,自动拉取目录树、README、issues、commits 等信息
- **自然语言问答**:围绕仓库提问,支持多轮对话与会话记忆
- **Agent 自主规划**:根据问题自动决定调用哪些 GitHub 工具获取上下文
- **RAG 检索增强**:仓库文档向量化后检索,回答带出处、更少幻觉
- **仓库分析报告**:一键生成仓库导读报告(定位、架构、上手指引)

## 技术栈

| 组件 | 选型 |
| --- | --- |
| 后端框架 | Spring Boot 3(REST API) |
| Agent / LLM 编排 | LangChain4j |
| 对话模型 | DeepSeek API |
| 向量化(Embedding) | LangChain4j 进程内 ONNX 模型 `bge-small-zh` |
| 业务数据 | MySQL |
| 会话记忆 | Redis |
| 效果评估 | Python 批量评测脚本 |

一期仅提供 REST API,不做前端。

## 项目状态

🚧 **开发中(v0.1)**

| 版本 | 内容 | 状态 |
| --- | --- | --- |
| v0.1 | 项目骨架、DeepSeek 接入、基础对话 + 会话记忆 | 进行中 |
| v0.2 | GitHub 工具集(目录树/README/issues/commits),Agent 自主规划调用 | 规划中 |
| v0.3 | 文档向量化 + RAG 问答、仓库分析报告 | 规划中 |
| v0.4 | 效果评估、Docker 部署、文档收尾 | 规划中 |

详细需求见 [docs/requirements.md](docs/requirements.md)。

## License

[MIT](LICENSE)
