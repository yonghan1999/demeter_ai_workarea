# 代码结构与责任链

后端按领域拆分：`auth`、`identity`、`bill`、`payment`、`ocr`、`audit`、`maintenance`、`migration`，通用能力位于 `common` 和 `security`。

每个业务用例由 `BusinessChain` 声明名称、执行模式和有序 Handler，统一交给 `BusinessChainExecutor` 执行。常见顺序是：解析当前操作者 → 校验输入/分页 → 加载并锁定数据 → 幂等解析 → 领域校验 → 持久化与副作用 → 审计 → 响应映射。

执行模式包括 `READ_ONLY`、`ATOMIC_DATABASE`、`EXTERNAL_IO`、`SCHEDULED`，同时产生 `demeter.business.chain.duration` 和 Handler 指标。新增用例应复用该模式，不绕过 Executor 直接拼装事务。
