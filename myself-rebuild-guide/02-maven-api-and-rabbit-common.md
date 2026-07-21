# 02. Maven 聚合、公共 API 与 RabbitMQ 公共模块

## 1. 为什么先写公共层

设备、订单、支付、认证会互相调用。如果先写服务实现，再临时复制 DTO，会出现：

- 同一个 `OrderInfo` 在多个服务各有一份，字段逐渐不一致；
- Feign 客户端依赖服务实现模块，形成循环依赖；
- fallback 组件没有被 Spring 自动导入；
- reactor 不知道新增模块，单模块能编译、根构建却漏掉。

因此先完成 Maven 图和稳定契约，再写业务。

## 2. 第一步：核对根依赖管理

`myself` 的根 `pom.xml` 已经保留了以下业务技术版本；这部分不是 `main` 相对 `myself` 的新增内容。复写时先核对版本，不要重复添加；只有缺少的依赖才补到对应模块的 `<dependencies>`。

| 属性 | 版本 | 为什么需要 |
|---|---:|---|
| `mybatis-plus.version` | 3.5.3.1 | 通用 Mapper、Service、分页、逻辑删除 |
| `lombok.version` | 1.18.30 | DTO/Entity 减少样板 getter/setter |
| `wxpay.version` | 0.2.11 | 微信支付 API v3、验签解密 |
| `weixin.miniapp.version` | 4.5.5.B | `code2Session` 换 openid |
| `drools.version` | 8.41.0.Final | 计费规则编译和执行 |
| `mqttv3.version` | 1.2.2 | Eclipse Paho MQTT 客户端 |

依赖管理只规定版本，不会自动把 jar 加进子模块。子模块仍要在 `<dependencies>` 显式声明使用的依赖。

### 推荐修正

根 POM 中 `redisson.version` 和 `org.redisson:redisson` 各声明了两次，Maven 会警告 dependency key 不唯一。复写时保留一份即可。也建议在 pluginManagement 明确 compiler/resources/surefire 版本，避免 Maven 使用非常老的默认插件。

真正需要从 `main` 恢复到 `myself` 的 POM 变化只有：根聚合 `share-api-rule`、`share-api` 聚合 user/order、`share-common` 聚合 rabbit、`share-modules` 聚合六个业务模块，以及 `share-modules` 对 `share-common-security` 的父级依赖。可用下面的差异命令核对：

```bash
git diff --unified=0 myself..main -- pom.xml share-api/pom.xml share-common/pom.xml share-modules/pom.xml
```

### 测试 2-1：有效 POM

```bash
mvn help:effective-pom -DskipTests >/tmp/share-effective-pom.xml
```

成功标准：退出码 0，且不再出现 duplicate redisson 警告。

## 3. 第二步：聚合模块

按 `main` 添加：

```xml
<!-- share-api/pom.xml -->
<modules>
    <module>share-api-system</module>
    <module>share-api-user</module>
    <module>share-api-order</module>
</modules>
```

`share-api-rule` 在 `main` 中直接以根 POM 为 parent，所以根 POM 额外写：

```xml
<module>share-api/share-api-rule</module>
```

```xml
<!-- share-common/pom.xml -->
<module>share-common-rabbit</module>
```

```xml
<!-- share-modules/pom.xml -->
<module>share-device</module>
<module>share-user</module>
<module>share-rule</module>
<module>share-order</module>
<module>share-payment</module>
<module>share-stastics</module>
```

### 为什么 parent 要正确

parent 决定 groupId、version、dependencyManagement 和插件继承。路径错误时，IDE 可能用本地缓存“看似正常”，CI 的干净构建却会失败。

### 测试 2-2：reactor 顺序

```bash
mvn validate -DskipTests
```

输出的 Reactor Build Order 应包含：`share-api-user`、`share-api-rule`、`share-api-order`、`share-common-rabbit` 和六个业务服务。

## 4. 第三步：公共 DTO

### 4.1 用户契约

`share-api-user` 中：

- `UserInfo`：数据库实体兼服务 DTO，继承 `BaseEntity`。
- `UserVo`：小程序只需昵称、头像、openid、押金状态。
- `UserCountVo`：统计日期和数量。

为什么单独用 `UserVo`：不应把手机号、状态、审计字段全部返回给小程序，输出模型应该小于持久化模型。

### 4.2 订单契约

`share-api-order` 中：

- `OrderInfo`、`OrderBill`；
- `SubmitOrderVo`：设备借出事件；
- `EndOrderVo`：设备归还事件；
- `OrderSqlVo`：原实现传任意 SQL，后续必须废弃；
- `UserInfoVo`：订单详情需要的用户摘要。

**注意 JavaBean 命名：** `SubmitOrderVo` 源码字段写成 `private Long UserId;`，Lombok 生成方法和 JSON 绑定容易产生大小写困惑。推荐改为 `private Long userId;`。

### 4.3 计费契约

`share-api-rule` 中：

- `FeeRule`：规则表实体；
- `FeeRuleRequestForm`：`duration`、`feeRuleId`（`main` 源码字段实际写成 `FeeRuleId`，复写时应改为小写开头）；
- `FeeRuleResponseVo`：总价、免费项、超时项；
- `RemoteFeeRuleService`。

金额使用 `BigDecimal`，不要用 `double` 作为服务边界。

### 测试 2-3：DTO JSON 契约

建议在各 API 模块增加 Jackson 测试：

```java
@Test
void submitOrderJsonUsesLowerCamelUserId() throws Exception {
    SubmitOrderVo vo = new SubmitOrderVo();
    vo.setUserId(7L);
    vo.setMessageNo("m-1");

    String json = objectMapper.writeValueAsString(vo);

    assertThat(json).contains("\"userId\":7");
    assertThat(objectMapper.readValue(json, SubmitOrderVo.class).getUserId())
        .isEqualTo(7L);
}
```

边界用例：金额 scale、日期格式、未知字段、null 字段、字段大小写。成功标准是生产者和消费者对同一 JSON 得到同一值。

## 5. 第四步：Feign 客户端

服务名来自 Nacos `spring.application.name`：

```java
@FeignClient(
    contextId = "remoteUserInfoService",
    value = ServiceNameConstants.SHARE_USER,
    fallbackFactory = RemoteUserFallbackFactory.class
)
public interface RemoteUserService {
    @GetMapping("/userInfo/getUserInfo/{id}")
    R<UserInfo> getInfo(@PathVariable("id") Long id);
}
```

### 为什么需要 `contextId`

一个应用可能声明多个指向同一服务的 Feign client；`contextId` 让 Spring Bean 名唯一，也隔离每个 client 的配置。

### 为什么共享模块要注册 fallback

共享 jar 中的 `@Component` 不一定落在业务应用默认 component scan 的预期范围内。`main` 在每个 `share-api-*` 的：

```text
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

列出 fallback factory，让 Spring Boot 3 自动导入。

### `main` 的 fallback 问题

rule fallback 正确返回一个失败 client；user/order fallback 的 `create(Throwable)` 直接抛 `ServiceException`，失去了方法级降级能力。推荐全部返回接口实现：

```java
@Override
public RemoteOrderInfoService create(Throwable cause) {
    log.error("订单服务调用失败", cause);
    return new RemoteOrderInfoService() {
        @Override
        public R<OrderInfo> getByOrderNo(String orderNo) {
            return R.fail("订单服务暂不可用");
        }
        // 其余方法同理
    };
}
```

调用方必须检查 `R.getCode()`，不能直接 `result.getData()` 后解引用。

### 内部接口鉴权

`main` 新增的 Feign 方法没有像 system API 那样传 `from-source=inner`，Controller 也没有 `@InnerAuth`。结果是接口依赖网关白名单来间接保护，边界不清晰。

推荐契约：

```java
@GetMapping("/userInfo/getUserInfo/{id}")
R<UserInfo> getInfo(
    @PathVariable("id") Long id,
    @RequestHeader(SecurityConstants.FROM_SOURCE) String source
);
```

服务端配 `@InnerAuth`；调用时传 `SecurityConstants.INNER`。面向会员的接口另开方法，不要共用内部任意 ID 查询。

### 测试 2-4：Feign 契约

可用 WireMock/MockWebServer 启动假下游：

1. 请求 `GET /userInfo/getUserInfo/7`；
2. 断言 path variable 为 7；
3. 断言 `from-source: inner`；
4. 返回 `R.ok` 时正确反序列化；
5. 返回 500/超时时 fallback 给确定失败，不返回 null。

## 6. 第五步：服务名常量

`ServiceNameConstants` 添加：

```java
public static final String SHARE_USER = "share-user";
public static final String RULE_SERVICE = "share-rule";
public static final String ORDER_SERVICE = "share-order"; // 推荐补齐
```

`main` 的 user 注释误写“文件服务”，order 又硬编码字符串。复写时统一常量可以减少拼写错误，但常量值必须与 Nacos 应用名完全相同。

## 7. 第六步：Rabbit 公共模块依赖

`share-common-rabbit` 需要：

- `spring-cloud-starter-bus-amqp`：带入 Spring AMQP/RabbitTemplate；
- `share-common-redis`：保存 correlation data 和重试次数；
- `fastjson2`：序列化 correlation data；
- Lombok。

如果不使用 Spring Cloud Bus，推荐改为更直接的 `spring-boot-starter-amqp`，职责更清楚。

## 8. 第七步：定义交换机、路由键、队列

`MqConst` 的业务拓扑：

| 事件 | exchange | routing key | queue |
|---|---|---|---|
| 借出建单 | `share.order` | `share.submit.order` | `share.submit.order` |
| 归还结算 | `share.order` | `share.end.order` | `share.end.order` |
| 支付成功 | `share.payment` | `share.payment.pay` | `share.payment.pay` |
| 锁槽超时 | `share.device` | `share.unlock.slot` | `share.unlock.slot` |

不要让 exchange、routing key、queue 共享同一个概念名称而不理解区别：exchange 负责路由，queue 才保存消费者要处理的消息。

## 9. 第八步：可靠发送

`RabbitService.sendMessage`：

1. 生成 correlation id；
2. 记录 exchange、routing key、payload、延迟、重试次数；
3. 用 correlation data 发送；
4. 把 correlation data 写 Redis 10 分钟；
5. confirm nack 或 return 时重发，最多三次。

### 为什么 correlation data 有用

Rabbit confirm 只说明消息是否到 exchange；当失败时，如果没有保存原交换机、路由键和 payload，就无法重发。

### `main` 的竞态与缺口

- 先 `convertAndSend` 后写 Redis，return callback 可能先触发，读取不到 correlation data。应先落 Redis，再发送。
- confirm ack 后没有删除 Redis key。
- 超过三次只写日志，没有失败消息表或告警。
- DB 提交与 MQ 发送不是一个原子操作，事务回滚时消息可能已经发出。
- 延迟消息 return 后直接放弃重试。

推荐最终使用 outbox：同一数据库事务写业务表和 `outbox_event`，后台发布成功后标记发送；消费者仍需幂等。

## 10. 第九步：手动确认与消费者幂等

生产者 confirm、消费者 ack、业务幂等解决的是三件不同事情：

- confirm：Broker 是否接收；
- ack：消费者是否完成；
- 幂等：同一业务消息重复执行是否只改变一次状态。

推荐消费者模板：

```java
public void consume(String content, Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    Event event = parseAndValidate(content);
    String key = "consume:event:" + event.getMessageNo();

    if (!idempotencyService.tryStart(key)) {
        channel.basicAck(tag, false); // 重复消息也必须确认
        return;
    }

    try {
        applicationService.handle(event);
        idempotencyService.markDone(key);
        channel.basicAck(tag, false);
    } catch (RetryableException ex) {
        idempotencyService.release(key);
        channel.basicNack(tag, false, true);
    } catch (Exception ex) {
        idempotencyService.markFailed(key, ex.getMessage());
        channel.basicNack(tag, false, false); // 进入 DLQ，不无限热循环
    }
}
```

`main` 在重复分支直接 `return`，没有 ack；manual ack 下消息会保持 unacked，连接关闭后再次投递。

## 11. Rabbit 公共模块测试

### 用例 2-5：发送成功

1. 声明测试 exchange/queue/binding。
2. `sendMessage`。
3. 从 queue 收一条消息。
4. 断言 payload 相同。
5. 等待 confirm callback，断言无重试。

### 用例 2-6：路由失败

1. exchange 存在但 routing key 无绑定。
2. 开启 `mandatory=true`。
3. 断言 returns callback 被调用。
4. 断言 Redis 在 callback 前已有 correlation data。
5. 断言达到最大次数后进入失败记录，而不是无限发。

### 用例 2-7：重复消费

同一 `messageNo` 连续投递两次：

- 两条 Rabbit 消息最终都 ack；
- 数据库只新增一条业务记录；
- 第二次日志明确是 duplicate；
- queue 的 ready/unacked 最终都为 0。

### 用例 2-8：处理失败

让数据库第一次抛可重试异常，第二次成功：

- 第一次 nack/requeue；
- 幂等锁被释放或保持“可重试”状态；
- 第二次实际执行业务并 ack；
- 最终只有一份结果。

## 12. 本阶段完成标准

- [ ] 根 reactor 包含所有新增模块。
- [ ] 三个 API 模块不依赖任何业务实现模块。
- [ ] DTO JSON 字段、金额和日期契约有测试。
- [ ] Feign 服务名、URL、method、header 有契约测试。
- [ ] fallback 不返回 null、不在工厂创建阶段直接失控抛错。
- [ ] Rabbit 发送前保存重试上下文，成功后清理。
- [ ] 重复消息被 ack，业务只执行一次。
- [ ] 所有常量都有唯一含义，`sendDealyMessage` 拼写改为 `sendDelayMessage`。

> **重点：** 先让共享契约稳定，后面的模块才能独立开发和模拟测试。

> **难点：** “消息至少一次投递”意味着重复是正常情况。幂等不是错误补丁，而是消费者设计的一部分。

> **注意：** 自动配置 imports 中应导入配置类，而不是随意列出普通 `@Service`。推荐创建一个 `RabbitAutoConfiguration`，用 `@Bean`/component scan 明确装配，再只导入该配置类。
