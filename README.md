# Demeter

货运账单微信小程序项目。小程序账单和 OCR 流程均通过服务层接入后端；后端基础服务已使用 Spring Boot 与 MySQL 实现，责任链、角色分离、迁移和测试体系都已落地。

## 项目结构

- `miniprogram/`：微信小程序源码
- `backend/`：Java 17 + Spring Boot + MySQL 后端服务
- `project.config.json`：微信开发者工具工程配置

## 已实现

- 账单列表、状态切换、全局搜索与同页高级筛选
- 当前筛选列表内批量管理、软删除与二次确认
- 手动新增/编辑、字段校验、托运人标准化查重、城市建议
- 拍照或相册选择、OCR 任务状态、失败重试、结果复核与合并
- 加载、空数据、失败、保存中、低可信和重复账单等状态
- 自定义导航栏、微信胶囊避让和底部 Safe Area 适配
- 后端具备责任链编排、幂等控制、审计、健康检查、生产角色划分和迁移校验

## 本地运行

使用微信开发者工具导入项目根目录：

```text
/Users/han/github/demeter
```

`project.config.json` 当前绑定目标测试小程序 AppID。核心账单请求使用 `https://demeter.nullptr.work/api/v1`，首次请求时通过微信登录换取 Bearer Token。

## 数据边界

账单和 OCR 通过 `miniprogram/services/bill-service.js` 与 `miniprogram/services/api-client.js` 访问后端；页面不直接依赖网络请求或持久化结构。搜索记录和 OCR 合并标记仅作为客户端 UI 状态缓存。

后端运行方式、接口清单与 OCR 扩展说明见 `backend/README.md`。

Codex 的团队配置与生产使用规范见 `docs/AI_ASSISTED_DEVELOPMENT.md`。

项目功能、运营流程、开发入门和 AI 阅读索引见 [`docs/mdbook/`](docs/mdbook/README.md)。

## 调试记录

- 设计基准：Figma 393 x 853 正式 Flow 画板
- 开发者工具：基础库 `3.16.2`
- 模拟设备：iPhone 12/13 (Pro)，390 x 844，底部安全区 34px
- 已验证：首页、筛选后批量管理、搜索建议/结果、表单校验/建议、OCR 拍照/任务/复核
- 已验证导航高度 87px，业务按钮不占用原生标题栏，固定底栏位于 Home Indicator 上方
- 微信接口测试号偶发 `webapi_getwxaasyncsecinfo:fail`，属于开发者工具安全信息接口，不影响页面业务运行
