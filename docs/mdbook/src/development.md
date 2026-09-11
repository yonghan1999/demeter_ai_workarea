# 开发新人手册

后端是 Java 17 + Spring Boot + Spring Data JPA + Flyway + MySQL；前端是微信小程序原生页面。先阅读根 `README.md`、`backend/README.md` 和 `backend/OPERATIONS.md`，再从对应领域目录进入代码。

开发要求：保持租户条件、幂等、审计、统一错误响应和责任链顺序；不要让页面直接访问网络或存储实现；不要把供应商 SDK 泄漏到 OCR SPI 之外。
