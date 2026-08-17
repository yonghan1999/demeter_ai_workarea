# Demeter

面向生产的微信小程序前端项目。当前阶段先实现前端与本地模拟数据，后端接口暂不实现。

## 当前范围

- 货运账单列表、搜索、筛选
- 手动新增与编辑账单
- 左滑标记已收款、删除
- 批量选择和批量删除
- OCR 拍照识别流程的前端模拟
- OCR 结果确认并合并到账单列表

## 打开方式

使用微信开发者工具导入当前目录 `/Users/han/github/demeter`。

当前 `appid` 使用 `touristappid`，可在微信开发者工具模拟器中本地调试。真机预览、上传和正式发布需要在 `project.config.json` 中替换为真实小程序 AppID。

## 数据说明

数据位于 `services/mock-store.js`，页面通过 `services/bill-service.js` 访问。后续接入后端时优先替换服务层，不需要大改页面。

## 调试记录

- 已使用微信开发者工具打开 `/Users/han/github/demeter`
- 首页、搜索页、新增账单页已在模拟器中确认可运行
- CLI 预览二维码因当前使用 `touristappid` 被微信拒绝，这是 AppID 限制，不是本地编译错误
