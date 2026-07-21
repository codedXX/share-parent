# `myself` 分支复写 `main` 业务代码：总导航

> 分析基准：`main@d8c87f5` 与 `myself@70fe881`，共同祖先为 `9baca20`。  
> 仓库中的真实分支名是小写 `myself`，不是 `mySelf`。

## 1. 这套文档解决什么问题

`myself` 在共同祖先上删除了共享充电宝业务代码，保留了若依微服务底座；`main` 保留完整的业务参考代码。因此复写工作不是从零造一个 Spring Cloud 项目，而是在已有底座上依次恢复：

1. Maven 聚合关系、公共领域对象、Feign 契约和 RabbitMQ 公共能力。
2. 微信小程序用户与 H5 Token 登录。
3. Drools 计费规则。
4. 站点、柜机、插槽、充电宝与 MongoDB 地理位置。
5. MQTT 设备协议和借还事件。
6. RabbitMQ 驱动的订单生命周期。
7. 微信支付。
8. Vue 管理端、小程序、统计模块。

文档不会让你机械复制 `main`。每个模块同时说明：

- 为什么需要该依赖和该层代码；
- 应该按什么顺序写；
- `main` 实际怎样运行；
- 每一步怎样测试，以及成功标准；
- `main` 中哪些地方只是演示实现、存在缺失或缺陷；
- 如果按可维护、可上线的标准复写，应怎样改进。

## 2. 必须先知道的事实

`main` 能作为业务代码参考，但仓库本身不是独立、完整的可运行交付物：

- 没有 `user_info`、`station`、`cabinet`、`cabinet_slot`、`power_bank`、`fee_rule`、`order_info`、`order_bill`、`payment_info` 等业务表 DDL。
- 没有 Nacos 中的 `application-dev.yml`、各服务数据源、Redis、RabbitMQ、MongoDB、网关动态路由与白名单配置。
- 没有微信支付证书和密钥，也不应该把这些机密提交到 Git。
- `mp-weixin` 是 uni-app 编译产物，不包含原始 `.vue`、`pages.json` 和前端构建工程。
- 管理端有若干前端请求没有对应后端接口，部分代码会构建失败或运行失败。
- MQTT 连接在 `main` 中被注释；地图距离使用随机数；支付金额固定为 1 分，都是演示逻辑。

所以文档中的内容分为三类：

| 标识 | 含义 | 复写要求 |
|---|---|---|
| **MAIN 原样** | `main` 中确实存在的实现 | 先理解，再决定是否复刻 |
| **缺失前提** | 代码依赖但仓库未提交的配置或数据 | 必须自己补齐 |
| **推荐实现** | 为修复明显问题给出的实现方式 | 建议直接按推荐版写 |

## 3. 推荐阅读和开发顺序

| 顺序 | 文档 | 这一阶段的交付物 |
|---:|---|---|
| 0 | [00-branch-diff-and-roadmap.md](00-branch-diff-and-roadmap.md) | 明确只复写差异范围，建立自己的提交节奏 |
| 1 | [01-architecture-and-infrastructure.md](01-architecture-and-infrastructure.md) | 启动 MySQL、Redis、Nacos、RabbitMQ、MongoDB、EMQX；配置网关 |
| 2 | [02-maven-api-and-rabbit-common.md](02-maven-api-and-rabbit-common.md) | Maven reactor 可识别全部模块，公共 API 模块可编译 |
| 3 | [03-user-and-h5-auth.md](03-user-and-h5-auth.md) | 微信 code 换 openid、创建用户、签发 Token |
| 4 | [04-fee-rule-and-drools.md](04-fee-rule-and-drools.md) | 计费边界测试全部通过 |
| 5 | [05-device-domain-crud-and-map.md](05-device-domain-crud-and-map.md) | 站点/柜机/插槽/充电宝 CRUD 和附近站点查询可用 |
| 6 | [06-mqtt-borrow-and-return.md](06-mqtt-borrow-and-return.md) | MQTT 模拟柜机可完成解锁与归还状态流转 |
| 7 | [07-order-and-rabbitmq.md](07-order-and-rabbitmq.md) | 借出建单、归还结算、支付成功改状态均幂等 |
| 8 | [08-wechat-payment.md](08-wechat-payment.md) | 预支付、回调验签、查单补偿链路可测 |
| 9 | [09-admin-ui.md](09-admin-ui.md) | 管理端业务页面和真实后端接口一一对应 |
| 10 | [10-mini-program.md](10-mini-program.md) | 登录、附近站点、扫码、订单、支付页面闭环 |
| 11 | [11-statistics-and-security.md](11-statistics-and-security.md) | 统计功能不执行任意 SQL，接口权限收口 |
| 12 | [12-end-to-end-test-plan.md](12-end-to-end-test-plan.md) | 完整借、还、付回归与失败补偿测试通过 |
| 13 | [13-known-issues-checklist.md](13-known-issues-checklist.md) | 对照检查 `main` 的已知缺陷，不把演示代码带入成品 |

不要一开始同时启动所有服务。每一阶段先让本模块的纯单元测试通过，再接 MySQL/Redis 等基础设施，最后才接下一项远程服务。这样错误范围最小。

## 4. 两条端到端主线

### 4.1 用户借出充电宝

```text
小程序 wx.login
  -> share-auth /h5/login/{code}
  -> share-user 用 code 换 openid，查询或创建 user_info
  -> share-auth 把 LoginUser 写 Redis 并签发 JWT
  -> 小程序扫码 cabinetNo
  -> share-device 校验押金和未完成订单
  -> 锁定最优插槽，向柜机发布 MQTT 解锁命令
  -> 柜机上报 /sys/powerBank/unlock
  -> share-device 更新 power_bank/cabinet_slot/cabinet
  -> RabbitMQ share.submit.order
  -> share-order 幂等创建 status=0 的 order_info
```

### 4.2 用户归还并支付

```text
柜机上报 /sys/powerBank/connected
  -> share-device 更新充电宝、插槽、柜机库存
  -> RabbitMQ share.end.order
  -> share-order 找到使用中订单
  -> share-rule 按时长计算费用
  -> share-order 写订单金额和 order_bill，状态改为 1（待支付）或 2（免费）
  -> 小程序调用 share-payment 创建微信预支付单
  -> 微信回调 /wxPay/notify
  -> share-payment 验签解密，payment_info 改为已支付
  -> RabbitMQ share.payment.pay
  -> share-order 状态改为 2
```

## 5. 每个阶段统一的完成定义

只有同时满足以下条件，才进入下一阶段：

1. **编译通过**：模块及其上游依赖可以用 Maven reactor 编译。
2. **正向测试通过**：正常输入产生预期响应和数据库状态。
3. **边界测试通过**：空值、边界时长、空库存、重复消息均有确定结果。
4. **失败测试通过**：远程服务、MQ、数据库异常时不会留下无法恢复的半状态。
5. **幂等测试通过**：同一个 `messageNo`、微信回调或重复请求执行两次，业务结果只发生一次。
6. **权限测试通过**：匿名、普通会员、后台管理员、内部 Feign 调用的权限边界明确。
7. **可观测**：日志能关联 `messageNo`、`orderNo`、`cabinetNo`、`powerBankNo`。

## 6. 分支操作建议

文档当前写在工作区中。你在 `myself` 开发时，建议每完成一个可独立验证的阶段就提交一次：

```bash
git switch myself
git status --short

# 每阶段完成并测试后
git add <本阶段文件>
git commit -m "feat: rebuild user service"
```

不要用 `git checkout main -- <整个业务目录>` 把答案一次性抄回去；这样会失去亲手建立依赖、定位错误和验证契约的过程。需要核对单个文件时使用：

```bash
git show main:share-modules/share-user/pom.xml
git diff myself..main -- share-modules/share-user
```

## 7. 测试命令约定

后续文档默认在仓库根目录执行命令：

```bash
# 只编译一个模块，并自动构建它依赖的同仓库模块
mvn -pl share-modules/share-user -am -DskipTests compile

# 执行一个模块的测试
mvn -pl share-modules/share-rule -am test

# 管理端
cd share-ui
npm install
npm run dev
npm run build:prod
```

接口示例统一使用以下变量：

```bash
export GATEWAY=http://localhost:18080
export TOKEN='<登录接口返回的 access_token>'
```

带 Token 的请求：

```bash
curl -i "$GATEWAY/user/userInfo/getLoginUserInfo" \
  -H "Authorization: Bearer $TOKEN"
```

## 8. 最终验收结果应该是什么

完成全部阶段后，你应能解释并证明：

- 为什么 Feign DTO 要放在 `share-api-*` 而不是服务实现模块；
- JWT、Redis 登录态、网关透传请求头和服务内 `SecurityContextHolder` 怎样串起来；
- 为什么设备库存更新和订单创建用事件解耦，又为什么仅有 MQ 仍不能自动保证一致性；
- MongoDB GeoJSON 坐标为什么必须按 `[longitude, latitude]` 存；
- MQTT QoS、业务幂等和数据库事务分别解决什么问题，为什么不能互相替代；
- Drools 规则如何避免重叠、边界多收费和金额精度问题；
- 微信支付为什么必须以后端验签回调或主动查单为准，不能相信小程序 `success` 回调；
- 怎样用一组可重复执行的测试证明借、还、付和异常补偿完整。

