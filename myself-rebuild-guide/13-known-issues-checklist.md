# 13. `main` 已知问题与复写检查清单

这份清单用于防止“代码和 main 一样”被误认为“实现正确”。优先级 P0 表示安全/资金/核心一致性，P1 表示主流程不可用，P2 表示质量或维护问题。

| 优先级 | 位置 | `main` 问题 | 复写时的完成条件 |
|---|---|---|---|
| P0 | `OrderInfoMapper.xml` | `${sql}` 执行 AI/调用方原始 SQL | 删除；固定参数化 SQL/受限 DSL |
| P0 | `WxPayServiceImpl` | 微信金额固定 `1` 分 | `realAmount` 精确换算为分并测试 |
| P0 | `PaymentInfoServiceImpl` | payment 成功后 MQ 失败会永久漏通知 | DB 事务 + outbox + 重试 |
| P0 | `OrderReceiver` | 两个方法监听同一 submit queue | 只保留一个消费者 |
| P0 | `OrderInfoServiceImpl.endOrder` | 订单和账单无事务 | 原子提交或可补偿 saga |
| P0 | `FeeRule.drl` | 免费规则与收费规则重叠 | 条件互斥、规则数断言 |
| P0 | `FeeRule.drl` | 65 分钟多收一档 | ceil 公式边界测试 |
| P0 | user/order/payment API | 按 id/orderNo 无所有权校验 | A 不能访问/支付 B |
| P0 | 内部 Feign provider | 无 `@InnerAuth` | from-source + provider 鉴权 |
| P0 | `isFreeDeposit` | GET 即免押 | 真正支付分/押金状态机和验签 |
| P0 | 地图 InfoWindow | 拼接数据库 HTML，存储型 XSS | escape/组件渲染 + 恶意输入测试 |
| P1 | `EmqxClientWrapper` | `connect()` 被注释 | 启动连接/readiness；失败不假成功 |
| P1 | `EmqxClientWrapper.publish` | 异常吞掉，扫码仍成功 | 返回失败、释放/补偿 slot |
| P1 | `MapServiceImpl` | 距离返回随机数 | 真实算法/API、单位和超时测试 |
| P1 | `PowerBankUnlockHandler` | sleep 5 秒阻塞 callback | 异步状态机，无阻塞 sleep |
| P1 | 设备 handler | 幂等键在校验/事务前写 | 参数先校验；失败可重试 |
| P1 | 设备库存 | 查询后普通更新，可并发超卖 | 条件更新/锁/不变量测试 |
| P1 | 设备/Rabbit | DB 与 MQ 非原子 | outbox |
| P1 | `OrderReceiver` | duplicate return 不 ack | 重复消息也 ack |
| P1 | payment bootstrap | Nacos namespace 与其他服务不一致 | discovery/config/route 同 namespace |
| P1 | 全仓库 | 业务 DDL/Nacos 配置未提交 | 脱敏迁移和配置模板可复现 |
| P1 | `UserInfoMapper.xml` | 缺 `selectUserInfoList` | Mapper 集成测试通过 |
| P1 | `FeeRuleController.edit` | 打印后固定成功，不更新 | updateById 后重新查询验证 |
| P1 | `share-ui` map page | 导入不存在的 `nearbyStation` | `npm run build:prod` 通过 |
| P1 | 管理端订单 | 前端接口无后端 mapping | 契约表全部匹配或删除残留 |
| P1 | `mp-weixin` | 只有编译产物，无 uni-app 源码 | 重建源码工程和测试 |
| P1 | 小程序 request | baseURL 固定 HTTP localhost | 环境化 HTTPS 合法域名 |
| P1 | 小程序位置 | 固定北京坐标 | 实际定位/拒绝授权备用路径 |
| P1 | 小程序轮询 | 无超时、无卸载清理 | timer 上限和生命周期测试 |
| P1 | rule Service | `feeRuleId/rule` 完全不参与计算 | 固定版本或动态规则策略明确 |
| P1 | order | 规则变更无版本快照 | 借出时保存 rule version |
| P1 | `PropertyPostHandler` | 空实现 | 不注册或实现遥测审计 |
| P2 | 根 POM | Redisson 属性/依赖重复 | effective POM 无 duplicate warning |
| P2 | `share-api-rule` | 聚合层与其他 API 不一致 | 统一 parent 或明确保留 |
| P2 | order/rule/payment POM | 打包插件/测试依赖不完整 | 可执行 jar、测试 scope 正确 |
| P2 | fallback factories | user/order create 直接抛异常 | 返回结构化 fallback，调用方检查 R |
| P2 | DTO | `UserId` 首字母大写 | `userId` JSON 契约测试 |
| P2 | `UserVo` | avatar 与 avatarUrl 不映射 | 显式 mapper |
| P2 | 统计 | 用户年份写死 2024、缺月不补 0 | year 参数 + 12 月完整序列 |
| P2 | 管理页面 | station logo/imageUrl、sex/gender 等字段错 | 表单契约测试 |
| P2 | MQ 演示 | 测试 exchange/controller 混在订单服务 | 移到 demo/test profile |
| P2 | 现有测试 | 多数只打印、无断言，payment 无测试 | 单元/集成/故障注入均有断言 |

## 复写策略

### 第一遍必须修正

所有 P0、主链路 P1。否则你虽然能演示页面，却无法证明资金、库存和订单一致。

### 可以后置

属性遥测、AI 自然语言、复杂管理报表、真实支付分、生产级告警平台。但后置功能应返回“未实现/禁用”，不能像 `main` 一样空方法或假成功。

### 每个提交的自查问题

1. 这一步的事实来源是什么：数据库、Redis、MQ、设备还是前端？
2. 同一请求/消息执行两次会怎样？
3. 在更新一半时进程崩溃会怎样恢复？
4. 当前用户是否有权操作目标资源？
5. 边界值、空值、并发和下游超时有测试吗？
6. 页面 success 是否代表数据库真实变化？
7. 配置/secret 能在新机器安全复现吗？

完成这张表后，再用 [12-end-to-end-test-plan.md](12-end-to-end-test-plan.md) 做最终签收。

