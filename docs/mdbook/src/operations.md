# 运营人员手册

运营工作的目标是让账单从录入到收款可追踪、可纠错。所有异常处理都应保留请求 ID、任务 ID 或账单 ID，禁止索要用户 Token 或在工单中粘贴密钥。

## 每日检查建议

- 查看 readiness：`/actuator/health/readiness` 应包含 `databaseSchema`、`roleReadiness` 且为 `UP`。
- 关注 5xx、P95 延迟、数据库连接池、OCR `PENDING/RETRYING` 数量和存储容量。
- 核对异常收款、重复请求和账单编辑冲突；优先通过界面重试，不直接改库。
- 确认备份任务和维护任务有成功记录。

详细流程见本章子页面及 `backend/OPERATIONS.md`。
