# AI 阅读索引

AI 处理需求时建议按以下顺序建立上下文：

1. 先读本页和[系统概览](overview.md)，确认功能是否属于 Mock、API、Worker 或 Maintenance。
2. 涉及接口时读 `backend/src/main/java/**/api` 与[接口约定](development/api.md)。
3. 涉及业务规则时读对应 `application`、`domain` 和 `common/chain`；检查租户、幂等、审计和并发控制。
4. 涉及前端时读[小程序页面与服务层](development/miniprogram.md)，不要直接修改 Mock Store 作为后端实现。
5. 涉及上线、密钥、迁移或 OCR 时必须同时阅读[发布与故障处理](operations/release.md)和 `backend/OPERATIONS.md`。

## 稳定术语

账单状态：`unpaid`、`partially_paid`、`paid`；OCR 状态：`pending`、`processing`、`retrying`、`succeeded`、`failed`；运行角色：`API`、`WORKER`、`MAINTENANCE`，数据库迁移由独立的 Migrator 进程负责。
