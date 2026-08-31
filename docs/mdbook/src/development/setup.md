# 本地环境与启动

## 小程序

使用微信开发者工具导入仓库根目录，基础库基准为 3.16.2。当前页面使用本地 Mock，不代表已完成后端联调；修改后应在开发者工具检查加载、空数据、失败、保存中和 Safe Area 状态。

## 后端

要求 JDK 17、MySQL 8（推荐 8.4）和 Maven Wrapper。复制 `backend/.env.example` 为本地 `.env`，启动 MySQL 后运行：

```bash
cd backend
docker compose --env-file .env up -d mysql
./mvnw spring-boot:run
```

服务默认 `http://localhost:8080`，健康检查在 `/actuator/health`，Swagger 和 OpenAPI 仅用于本地/非生产调试。

## 验证命令

```bash
sh scripts/ai-harness-preflight.sh
cd backend && ./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/release-preflight.sh
docker compose config
```
