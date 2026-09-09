# Demeter Backend Operations

本文档描述生产环境部署、观测、备份和故障处理要求。生产实例必须启用 `prod` Profile，并通过 HTTPS 反向代理或负载均衡器对外服务。

每次生产发布都应同步执行 `RELEASE_CHECKLIST.md`。

## 必需配置

以下变量缺失时，生产实例应拒绝启动：

- `DB_URL`：MySQL 8.4 JDBC 地址，必须启用 TLS。
- `DB_USERNAME`、`DB_PASSWORD`：按运行角色分配的独立账号，禁止使用 `root` 或共享高权限账号。
- `WECHAT_APP_ID`、`WECHAT_APP_SECRET`：正式小程序凭据，来自密钥管理服务。

常用可选变量：

- `SERVER_PORT=8080`、`MANAGEMENT_PORT=9090`
- `AUTH_SESSION_TTL=30d`
- `OCR_STORAGE_PATH=/var/lib/demeter/ocr`
- `OCR_WORKER_ENABLED=false`
- `OCR_MAX_ATTEMPTS=3`
- `OCR_QWEN_ENABLED=false`：是否启用阿里云百炼 `qwen3-vl-plus` OCR 适配器；仅 OCR Worker 应启用。
- `DASHSCOPE_API_KEY`：从密钥管理服务注入，禁止写入镜像、Git、日志或错误响应。
- `DASHSCOPE_CHAT_COMPLETIONS_URL`：与 API Key 同地域的 HTTPS Chat Completions Endpoint；生产优先使用业务空间专属域名。
- `OCR_DOCUMENT_RETENTION=30d`
- `SESSION_RETENTION=7d`

不要把 `.env`、微信密钥、数据库密码或阿里云凭据写入镜像、Git、日志或错误响应。
生产变量可参考 `.env.production.example`，真实值必须来自密钥管理服务或部署平台。

## 部署

构建：

```bash
./mvnw --batch-mode clean verify
./mvnw --batch-mode -Pmysql-it verify
docker build -t demeter-backend:<version> .
```

容器以 UID/GID `10001` 运行。挂载的 OCR 目录必须可由该用户写入；多实例部署时必须使用共享对象存储适配器，不能使用节点本地目录。

推荐拆为四种运行角色：

- Migrator：只负责数据库迁移，不暴露业务流量
- API 实例：`DEMETER_RUNTIME_ROLE=API`
- OCR Worker 实例：`DEMETER_RUNTIME_ROLE=WORKER`
- 维护实例：`DEMETER_RUNTIME_ROLE=MAINTENANCE`

Flyway 不应由 API、Worker 或 Maintenance 进程在生产启动时自动执行。一次只能发布向后兼容的 expand/contract 迁移；破坏性字段删除必须跨版本完成。生产迁移前先做快照，并在影子库执行同一版本迁移。数据库 schema 最低版本必须在发布前验证通过。

## 上线门禁

CI 必须通过以下门禁后才允许发布：

- `scripts/release-preflight.sh`
- H2 场景的 `clean verify`，包括打包和覆盖率检查
- MySQL 8.4 Testcontainers 集成测试
- Gitleaks 密钥扫描
- OWASP Dependency Check
- Docker 镜像构建、非 root 运行身份和 Healthcheck 检查
- Trivy 镜像漏洞扫描
- TLS MySQL 下的 migrator 迁移、角色授权验证和容器启动冒烟

目标环境发布前还必须现场确认：

- MySQL TLS 证书链、DNS 名称和 `sslMode=VERIFY_IDENTITY`
- API、Worker、Maintenance、Migrator 四类数据库账号权限与密码托管
- `/actuator/health/readiness` 包含 `databaseSchema` 和 `roleReadiness` 且为 `UP`
- 管理端口只在内网或本机可访问，`/actuator/info` 和 `/actuator/prometheus` 需要管理 Token
- 备份恢复演练已完成，并记录恢复点、恢复时长和验收人
- 前后端联调、真机登录、账单查询、收款、软删除恢复和 OCR 上传契约已通过

可在目标环境设置以下变量后执行现场预检：

```bash
PREFLIGHT_READINESS_URL=http://127.0.0.1:9090/actuator/health/readiness \
PREFLIGHT_MANAGEMENT_INFO_URL=http://127.0.0.1:9090/actuator/info \
scripts/release-preflight.sh
```

## 健康与观测

管理端口不得暴露公网：

- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/prometheus`

建议告警：

- 5xx 比例持续 5 分钟超过 1%
- P95 API 延迟超过 1 秒
- Hikari 活跃连接接近上限
- OCR `PENDING/RETRYING` 数量持续增长
- `demeter.business.chain.duration` 失败率或耗时异常
- 磁盘或对象存储容量低于 20%
- MySQL 复制延迟、锁等待或连接数异常

日志为 ECS JSON，并包含 `requestId`。客户端上报问题时应提供响应头 `X-Request-Id`，不要要求用户提供访问 Token。

## 备份与恢复

- MySQL 每日全量备份，并开启时间点恢复日志；备份加密后保存到独立账号。
- OCR 原图使用对象存储版本控制和服务端加密；完成 30 天后由维护任务删除。
- 至少每季度在隔离环境执行一次恢复演练。
- 恢复验收必须覆盖租户、账单、支付流水、审计、OCR 结果和会话撤销状态。

建议目标：`RPO <= 15 分钟`，`RTO <= 2 小时`。实际目标需由业务负责人确认。

## 发布与回滚

1. 在 CI 通过 H2、MySQL 8.4、JAR 和镜像构建门禁。
2. 备份数据库并确认恢复点。
3. 先发布一个实例，确认 readiness、登录、账单查询和收款冒烟测试。
4. 逐步扩大流量并观察错误率、延迟和数据库连接。
5. 应用回滚只能回到兼容当前 schema 的版本；数据库迁移默认前向修复，不直接回滚已执行脚本。

## 故障处置

## 管理台运维操作

管理台仅在 API 角色加载，访问路径为 `/admin`。启用前必须通过环境变量注入至少 32 个字符的 `ADMIN_ACCESS_TOKEN`，并限制 API 入口只允许内网或 VPN 访问。

管理台提供以下受审计操作：

- 暂停/恢复租户，以及禁用/启用用户；状态变化会立即影响后续微信登录鉴权。
- 撤销指定用户的全部有效登录会话；适用于账号疑似泄露、设备遗失或人员离职场景。
- 撤销指定租户下全部用户的有效登录会话；适用于租户整体接管、批量设备泄露或紧急隔离场景。
- 账单软删除/恢复；不会物理删除账单或支付流水。
- 账单列表支持当前页选中后事务化批量软删除（单次最多 100 笔）；任一账单不存在、已删除或发生并发冲突时全部回滚。
- 查看收款流水并冲正收款；冲正保留原支付记录，只反向调整账单已收金额。
- 失败 OCR 任务重试；源文件清理后不可重试，供应商未配置时不会伪造任务成功。

管理台查询页支持关键词、租户 ID、状态和对象筛选，并保留分页条件；账单默认包含有效和已删除记录，便于定位恢复目标。收款页可进入“支付对账异常”只读页，展示账单已收金额与有效流水合计的差额。发现差额后应按对账流程核查原始请求和审计日志，禁止直接改库或使用管理台绕过账本规则。

“维护运行”页面只读展示独立 MAINTENANCE 角色最近的清理运行状态、耗时、清理计数和失败摘要。API 管理台不会触发维护任务；出现 FAILED 或 PARTIAL_FAILURE 时，应根据运行记录和日志处理，不要在 API 容器中直接删除数据。

所有写操作均要求 CSRF Token、操作原因和幂等键，并写入 `audit_events`。管理会话只在数据库保存随机令牌的 SHA-256 摘要；不得在日志、工单或审计详情中记录访问口令或 Cookie 原文。当前认证模型仍是共享访问口令，管理员用户名显示为 `configured-admin`，不具备 RBAC；需要多人分权时应先建设独立管理员身份和权限模型。

- 微信登录异常：检查微信 API 状态和凭据，按 `X-Request-Id` 定位；不要记录带 `secret` 的请求 URL。
- OCR 积压：先暂停新 Worker 扩容操作，检查供应商限流；任务租约过期后可自动重新认领。
- OCR 未配置：任务进入 `FAILED/OCR_NOT_CONFIGURED`，配置供应商后由用户或运维触发重试。
- Qwen OCR 认证失败：核对 API Key 与 Endpoint 是否同地域、模型服务是否已开通；不要在工单或日志中提交 API Key。
- Qwen OCR 限额：任务以 `OCR_PROVIDER_QUOTA_EXCEEDED` 失败；检查百炼余额、配额和限流后再重试。
- OCR 识别未配置：生产 Worker 会因缺少唯一 Provider 拒绝启动；本地一体化环境中的任务会进入
  `FAILED/OCR_NOT_CONFIGURED`，不会写入正式账单。
- 重复收款请求：客户端必须复用原 `Idempotency-Key`；同键不同内容返回 409。
- 账单编辑冲突：客户端重新获取账单和 `ETag`，合并后使用新的 `If-Match` 重试。
- 数据误删：账单采用软删除，禁止直接修改生产库；通过受审计的恢复流程处理。
