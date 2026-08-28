# Demeter Backend

Demeter 微信小程序后端服务，使用 Java 17、Spring Boot、Spring Data JPA、Flyway 和 MySQL。

## 当前能力

- 账单分页查询、关键词搜索、状态/日期/托运人/标签筛选
- 账单详情、新增、编辑和收款状态更新
- 账单软删除、恢复与事务化批量软删除
- 托运人建议、名称归一化和搜索建议
- RFC 9457 风格统一错误响应与 `X-Request-Id` 请求追踪
- Actuator 健康检查与本地开发 OpenAPI/Swagger 文档
- OCR 上传契约和厂商适配接口；识别实现暂未接入
- 统一责任链驱动的业务编排、幂等控制和并发控制

## 技术要求

- JDK 17
- MySQL 8.0+，推荐 MySQL 8.4 LTS
- 可选：Docker Compose，用于启动本地 MySQL

本机若通过 Homebrew 安装 Java 17，但 Maven Wrapper 找不到 Java，可先设置：

```bash
export JAVA_HOME="$(brew --prefix openjdk@17)"
```

## 本地启动

1. 创建本地环境文件：

```bash
cp .env.example .env
```

生产变量模板见 `.env.production.example`，真实值应来自密钥管理或部署平台，不要把生产 `.env` 提交到 Git。

2. 修改 `.env` 中的密码，然后启动 MySQL：

```bash
docker compose --env-file .env up -d mysql
```

3. 将数据库配置导入当前终端并启动服务：

```bash
set -a
source .env
set +a
./mvnw spring-boot:run
```

默认地址：

- 服务：`http://localhost:8080`
- 健康检查：`http://localhost:8080/actuator/health`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`

OpenAPI/Swagger 仅用于本地和非生产调试。生产 Profile 默认关闭，并由启动校验阻止重新开启。

也可以直接配置 `DB_URL`，它的优先级高于 `DB_HOST`、`DB_PORT` 和 `DB_NAME`。

## 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/bills` | 分页查询与筛选账单 |
| `GET` | `/api/v1/bills/{id}` | 获取账单详情 |
| `POST` | `/api/v1/bills` | 新增账单 |
| `PUT` | `/api/v1/bills/{id}` | 编辑账单 |
| `PATCH` | `/api/v1/bills/{id}/status` | 更新收款状态 |
| `DELETE` | `/api/v1/bills/{id}` | 软删除单笔账单 |
| `POST` | `/api/v1/bills/batch-delete` | 事务化批量软删除 |
| `POST` | `/api/v1/bills/{id}/restore` | 恢复软删除账单 |
| `GET` | `/api/v1/bills/shipper-suggestions` | 托运人建议 |
| `POST` | `/api/v1/bills/resolve-shipper` | 托运人名称归一化 |
| `GET` | `/api/v1/bills/search-suggestions` | 搜索建议 |
| `POST` | `/api/v1/ocr/recognitions` | 上传手写账单图片进行识别 |

账单列表支持以下查询参数：

- `keyword`、`code`、`shipper`、`status`
- `startDate`、`endDate`，格式为 `yyyy-MM-dd`
- `tag`、`page`、`size`
- `sort`，例如 `date,desc`；仅允许文档定义的字段

## OCR 接入边界

OCR 接口当前已定义，但没有识别实现。在未注册识别提供方时，上传接口返回 `503 Service Unavailable`，响应码为：

```json
{
  "status": 503,
  "code": "OCR_NOT_CONFIGURED",
  "detail": "OCR recognition is not configured"
}
```

已提供阿里云百炼 `qwen3-vl-plus` 适配器，仍保持在
`HandwrittenBillOcrProvider` 边界之后，Controller 和应用服务无需改动。默认关闭；仅在
OCR Worker 上配置以下变量后启用：

```text
OCR_QWEN_ENABLED=true
DASHSCOPE_API_KEY=<由密钥管理服务注入>
DASHSCOPE_CHAT_COMPLETIONS_URL=https://<workspace-id>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions
```

API Key 与 Endpoint 必须属于同一阿里云地域。原始账单图片会以 Base64 data URL 发送给
阿里云百炼，因此该接入产生模型调用费用并增加第三方数据处理与可用性依赖；应在启用前完成
数据合规评审、费用额度与告警配置。适配器只保留经过字段校验的结构化结果，不持久化厂商原始
响应体或 API Key。公开协议参见阿里云[OpenAI Chat 兼容文档](https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope)。

## 数据库迁移

生产运行时不自动执行 Flyway。数据库 schema 由独立迁移进程 `DatabaseMigrationMain` 或容器中的 migrator 角色预先应用，运行时进程只做 schema 校验和健康检查。

`src/main/resources/db/migration` 中的版本化脚本按顺序执行；Hibernate 使用 `validate`，不会在生产环境自动修改表结构。

## 测试与打包

```bash
./mvnw test
./mvnw clean package
```

默认测试使用 H2 的 MySQL 兼容模式，不依赖本地 MySQL；CI 额外运行 MySQL 8.4 Testcontainers 集成测试、镜像构建、镜像扫描、角色权限验证和容器启动冒烟。

## 上线前事项

后端已具备微信登录、Bearer Token、租户隔离、生产角色划分和真实 MySQL 8.4 CI 门禁。正式发布前仍需完成目标环境的 Docker/MySQL 现场验证、密钥托管、备份恢复演练、前后端联调和阿里云 OCR 适配实现；发布执行项见 `RELEASE_CHECKLIST.md`。
