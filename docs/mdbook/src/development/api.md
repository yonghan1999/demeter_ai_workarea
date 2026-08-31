# 接口与数据约定

基础路径为 `/api/v1`。认证：`POST /auth/wechat/login`、`POST /auth/logout`；业务请求使用 Bearer Token。

| 资源 | 主要接口 |
| --- | --- |
| 账单 | `GET/POST /bills`、`GET/PUT/DELETE /bills/{id}`、`POST /bills/batch-delete`、`POST /bills/{id}/restore` |
| 建议 | `GET /bills/shipper-suggestions`、`POST /bills/resolve-shipper`、`GET /bills/search-suggestions` |
| 收款 | `POST/GET /bills/{billId}/payments`、`POST /bills/{billId}/payments/{paymentId}/reversal` |
| OCR | `POST/GET /ocr/tasks`、`GET /ocr/tasks/{id}`、`POST /ocr/tasks/{id}/retry` |

写操作的幂等键最长 128 字符；分页 `page` 从 0 开始，`size` 受服务端上限约束。统一错误响应遵循 RFC 9457 风格，并通过 `X-Request-Id` 追踪。账单详情返回版本 ETag，更新可用 `If-Match`。

OCR 上传支持 JPEG、PNG、WebP、HEIC、HEIF，大小、尺寸和像素数受配置限制。
