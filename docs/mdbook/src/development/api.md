# 接口与数据约定

基础路径为 `/api/v1`。认证：`POST /auth/wechat/login`、`POST /auth/logout`；业务请求使用 Bearer Token。

| 资源 | 主要接口 |
| --- | --- |
| 用户昵称 | `GET /users/me`、`PUT /users/me/nickname`（Bearer Token，仅当前用户） |
| 账单 | `GET/POST /bills`、`GET/PUT/DELETE /bills/{id}`、`POST /bills/batch-delete`、`POST /bills/{id}/restore` |
| 建议 | `GET /bills/shipper-suggestions`、`POST /bills/resolve-shipper`、`GET /bills/search-suggestions` |
| 收款 | `POST/GET /bills/{billId}/payments`、`POST /bills/{billId}/payments/{paymentId}/reversal` |
| OCR | `POST/GET /ocr/tasks`、`GET /ocr/tasks/{id}`、`POST /ocr/tasks/{id}/retry` |

写操作的幂等键最长 128 字符；分页 `page` 从 0 开始，`size` 受服务端上限约束。统一错误响应遵循 RFC 9457 风格，并通过 `X-Request-Id` 追踪。账单详情返回版本 ETag，更新可用 `If-Match`。

昵称更新请求为 `{ "displayName": "用户选择或填写的昵称" }`，必填、最多 120 字符，保存前移除首尾空白。响应包含 `id`、租户公开标识 `tenantId`、`displayName`；不接受目标用户或租户参数，不返回 OpenID。更新使用租户限定行锁并记录审计，与现有会话共存；重复保存相同昵称不会重复产生修改审计。小程序通过 `services/profile-service.js` 调用并更新当前用户缓存，账号变化时拒绝回写，静默登录不会覆盖已保存昵称。

OCR 上传支持 JPEG、PNG、WebP、HEIC、HEIF，大小、尺寸和像素数受配置限制。

`POST /ocr/tasks` 使用 multipart 字段 `image` 上传图片，可选文本字段 `recognitionHint` 最长 500 字，首尾空白会移除。省略或空白说明保留原上传行为；说明随任务保存并用于所有重试。同一幂等键只能重复提交相同图片和相同规范化说明，变更说明返回 `409 Conflict`。
