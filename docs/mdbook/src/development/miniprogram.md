# 小程序页面与服务层

已注册页面位于 `miniprogram/pages/`：`home` 首页、`search` 搜索、`bill-form` 表单、`bill-export` 对账单导出、`ocr-camera` 拍照、`ocr-tasks` 任务列表、`ocr-review` 结果复核。高级筛选和批量管理当前由首页处理；通用 UI 位于 `miniprogram/components/`。

页面通过 `services/bill-service.js` 处理账单和 OCR，导出页通过 `services/bill-export-service.js` 生成文件；网络请求由 `services/api-client.js` 管理，Token 不由页面处理。收款调用 `POST /api/v1/bills/{billId}/payments` 写入流水；OCR 上传调用 `POST /api/v1/ocr/tasks` 创建异步任务。客户端仅缓存搜索记录和已合并任务标记等界面状态。导航采用自定义样式并避让微信胶囊，底部固定操作区需位于 Safe Area 之上。
