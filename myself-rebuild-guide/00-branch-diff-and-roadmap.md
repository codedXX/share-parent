# 00. 分支差异与复写路线

## 1. 先正确理解两个分支

仓库实际关系：

```text
1e36359 ... 9baca20  共同历史，已经包含完整业务
                    |\
                    | d8c87f5  main：修正归还订单消费者，新增 DroolsHelper
                    |
                    70fe881    myself：删除业务代码，保留若依底座
```

关键命令：

```bash
git branch --all --no-color
git merge-base main myself
git log --oneline --decorate --graph --all
git diff --stat myself..main
git diff --name-status myself..main
```

本次核对结果：

- `main`：`d8c87f5`
- `myself`：`70fe881`
- 共同祖先：`9baca20f49b012c6816a7e32bb962b749887e349`
- `myself..main`：287 个文件有差异，其中 266 个新增、21 个修改。
- 约 21,071 行新增、977 行删除；大部分删除来自 `myself` 主动移除业务代码。

**为什么必须先做这一步：** 如果误以为 `main` 是在 `myself` 之后逐次开发出来的，就会按 Git 提交历史得到错误的实现顺序。真实可学习顺序需要根据模块依赖和业务时序重新设计。

## 2. `myself` 已经保留了什么

`myself` 仍保留若依 Cloud 底座：

- `share-gateway`：Spring Cloud Gateway、验证码、JWT 校验、白名单和请求头注入。
- `share-auth`：后台账号密码登录、登出、刷新、注册。
- `share-modules/share-system`：后台用户、角色、菜单、字典等。
- `share-modules/share-gen`、`share-job`、`share-file`。
- `share-common-core`、`security`、`redis`、`log`、`datascope` 等公共模块。
- `share-api-system`。
- Vue3 若依管理端基础框架。

因此不需要重写整个若依底座。第一遍复写应聚焦 `myself..main` 的业务差异；遇到底座 API 时再阅读相应源码。

## 3. 需要恢复的代码范围

### 3.1 新模块

| 聚合层 | 模块 | 职责 |
|---|---|---|
| `share-api` | `share-api-user` | 用户 DTO、Feign 客户端、降级工厂 |
| `share-api` | `share-api-order` | 订单 DTO、Feign 客户端、降级工厂 |
| 根聚合 | `share-api-rule` | 计费 DTO、Feign 客户端、降级工厂 |
| `share-common` | `share-common-rabbit` | MQ 常量、发送、确认回调、重试 |
| `share-modules` | `share-user` | 微信登录、会员信息、免押状态、会员统计 |
| `share-modules` | `share-rule` | 计费规则 CRUD 与 Drools |
| `share-modules` | `share-device` | 站点、柜机、充电宝、地图、MongoDB、MQTT |
| `share-modules` | `share-order` | 建单、归还结算、订单查询、MQ 消费 |
| `share-modules` | `share-payment` | 微信支付 V3、支付记录、支付通知 |
| `share-modules` | `share-stastics` | 用户统计和实验性的 AI SQL 统计 |
| 独立目录 | `drools_project` | Drools 入门演示，不属于根 Maven reactor |
| 客户端 | `mp-weixin` | uni-app 编译后的小程序产物 |

### 3.2 管理端业务代码

`share-ui` 新增或大改以下页面/API：设备、站点、地图、费用规则、会员、订单和统计。管理端路由依赖数据库 `sys_menu` 动态返回，但仓库没有这些业务菜单 SQL，这也是缺失前提。

### 3.3 底座上的小改动

- 根 `pom.xml` 把 `share-api-rule` 加入 reactor。
- `share-api/pom.xml` 聚合 user/order；rule 反而在根目录直接聚合。
- `share-common/pom.xml` 聚合 rabbit。
- `share-modules/pom.xml` 聚合六个业务服务。
- `ServiceNameConstants` 增加 `share-user`、`share-rule`。
- `share-auth` 加 user API 依赖和 H5 登录。
- 网关/各服务 `bootstrap.yml` 改成作者局域网 Nacos 地址。
- `BaseController`、`HeaderInterceptor` 的部分差异只是格式化，不影响行为。

## 4. 不要照 Git 文件列表顺序写

正确的依赖顺序是：

```text
share-common-core（已存在）
  ├─ share-api-user ───────────────┐
  ├─ share-api-rule ───────────┐   │
  └─ share-api-order ──────┐   │   │
                           │   │   │
share-common-redis（已存在）│   │   │
  └─ share-common-rabbit ───┼───┼───┤
                           │   │   │
share-user  <───────────────┘   │   │
share-rule  <───────────────────┘   │
share-order < api-order + api-rule + api-user + rabbit
share-device< api-order + api-rule + api-user + rabbit
share-payment< api-order + api-user + rabbit
share-auth   < api-user
share-stastics< api-order + api-user
```

**为什么公共 API 先写：** 服务 A 调用服务 B 时，A 只能依赖稳定契约，不能依赖 B 的整个实现 jar，否则会形成模块耦合甚至循环依赖。DTO 和 Feign interface 放在 `share-api-*`，服务实现只依赖契约。

## 5. 建议的提交里程碑

| 提交 | 内容 | 最小测试 |
|---|---|---|
| 1 | 聚合 POM、`share-api-*` 空骨架 | `mvn help:effective-pom`，reactor 能发现模块 |
| 2 | DTO、Feign、fallback | DTO 序列化测试，Feign 路径契约测试 |
| 3 | Rabbit 公共模块 | Testcontainers Rabbit/Redis 或本地消息确认测试 |
| 4 | user + H5 auth | 模拟 `WxMaService`，首次/再次登录测试 |
| 5 | rule + Drools | 0、5、6、65、1445 分钟边界测试 |
| 6 | device CRUD + Mongo 地理查询 | Mapper、事务、Geo 查询测试 |
| 7 | MQTT 协议与处理器 | 协议单测、mosquitto 模拟借还 |
| 8 | order | 重复消息、结算回滚、状态机测试 |
| 9 | payment | 验签回调、重复回调、MQ 失败补偿测试 |
| 10 | 管理端 | `npm run build:prod` + 页面接口检查 |
| 11 | 小程序源码工程 | 微信开发者工具与真机测试 |
| 12 | stats + 安全收口 | SQL 白名单、权限矩阵、端到端测试 |

## 6. 每次核对 `main` 的方法

查看参考文件，不切换分支：

```bash
git show main:share-modules/share-order/src/main/java/com/share/order/service/impl/OrderInfoServiceImpl.java
```

只看自己当前实现和参考差异：

```bash
git diff main -- share-modules/share-order
```

看 `myself` 原始状态到参考实现的清单：

```bash
git diff --name-status myself..main -- share-modules/share-order
```

查看 `main` 最后一笔修正：

```bash
git diff 9baca20..main -- \
  share-modules/share-order/src/main/java/com/share/order/receiver/OrderReceiver.java \
  share-modules/share-rule/src/main/java/com/share/rules/config/DroolsHelper.java
```

## 7. 阶段 0 测试

### 用例 0-1：确认没有在错误分支开发

```bash
test "$(git branch --show-current)" = "myself"
```

预期：退出码为 0。若文档先保留在别的分支，应先决定怎样携带文档，再开始业务代码。

### 用例 0-2：确认工作区没有误带 `main` 的业务目录

```bash
git status --short
git diff --name-only
```

预期：只显示你明确创建的文件，不应突然出现数百个从 `main` 恢复的业务文件。

### 用例 0-3：确认底座仍然存在

```bash
for path in \
  pom.xml \
  share-gateway/pom.xml \
  share-auth/pom.xml \
  share-common/share-common-core/pom.xml \
  share-modules/share-system/pom.xml \
  share-ui/package.json
do
  test -f "$path" || exit 1
done
```

预期：退出码 0。

## 8. 重难点标记

> **重点：** Git 差异只告诉你最终缺哪些文件，不告诉你正确开发顺序。

> **难点：** `main` 的外部配置和业务数据库不在 Git 中，不能仅靠复制 Java 文件得到可运行系统。

> **注意：** `share-api-rule` 的 parent 与 user/order 不一致。`main` 把它直接加到根 `<modules>`，但没有加到 `share-api/pom.xml`。复写时可以忠实保持，也可以统一到 `share-api` 聚合；如果调整，必须同时改 parent/relativePath 并用 reactor 测试。

> **注意：** 不要把作者的 `192.168.139.194`、Nacos namespace、微信证书路径或地图 key 原样抄进自己的提交。

