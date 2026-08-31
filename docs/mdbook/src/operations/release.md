# 发布、监控与故障处理

生产必须使用 `prod` Profile、HTTPS 和独立数据库账号（禁止 root）。运行角色分为 Migrator、API、WORKER、MAINTENANCE；Flyway 仅由 Migrator 执行。

## 发布门禁

CI 需通过 `clean verify`、MySQL 集成测试、密钥扫描、依赖漏洞扫描、镜像非 root/Healthcheck 检查和 `scripts/release-preflight.sh`。发布先单实例冒烟，再逐步放量。数据库迁移采用向前兼容的 expand/contract 策略。

## 关键指标

建议告警：5xx 持续 5 分钟超过 1%、P95 超过 1 秒、连接池接近上限、OCR 积压增长、责任链失败率异常、存储低于 20%、MySQL 锁等待或复制延迟异常。

## 回滚与恢复

应用只能回滚到兼容当前 schema 的版本；已执行迁移不直接回滚。MySQL 每日全量备份并开启时间点恢复，目标建议 RPO ≤15 分钟、RTO ≤2 小时；至少每季度演练一次。
