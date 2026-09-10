# 小程序页面与服务层

页面位于 `miniprogram/pages/`：`home` 首页、`search` 搜索、`filter` 筛选、`select` 批量选择、`bill-form` 表单、`ocr-camera` 拍照、`ocr-tasks` 任务列表、`ocr-review` 结果复核。通用 UI 在 `components/app-nav` 和 `components/bill-card`。

页面只调用 `services/bill-service.js`。核心账单通过 `services/api-client.js` 调用测试环境 API，Token 由服务层管理；OCR 临时流程仍使用 `services/mock-store.js`。导航采用自定义样式并避让微信胶囊，底部固定操作区需位于 Safe Area 之上。
