# OCR 任务运营

## 用户流程

小程序支持拍照或从相册选择图片，创建任务后在 OCR 任务列表查看进度；识别完成进入复核页，低可信字段和重复账单会被标记，确认后可合并为正式账单。

## 后端状态机

`pending → processing → succeeded`；失败任务进入 `failed`，重试时为 `retrying` 后再次处理。上传接口返回 `202 Accepted` 和任务地址，查询接口按任务 ID 获取结果。

## 异常处理

- 未配置供应商：接口返回 `503 OCR_NOT_CONFIGURED`，不会写入正式账单。
- 供应商限额：任务失败码为 `OCR_PROVIDER_QUOTA_EXCEEDED`，检查额度和限流后重试。
- 认证失败：核对 API Key 与 Endpoint 是否同地域；不要在日志或工单中提交密钥。
- 任务积压：观察租约和 Worker 日志，先确认供应商限流，再扩容 Worker。

原图按 `OCR_DOCUMENT_RETENTION` 保留（默认 30 天），由 Maintenance 清理。多实例部署必须使用共享对象存储适配器。
