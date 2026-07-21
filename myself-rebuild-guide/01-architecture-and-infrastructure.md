# 01. 总体架构与基础设施

## 1. 先理解请求和事件的两条通道

系统同时使用同步调用和异步事件：

| 通道 | 技术 | 适合做什么 | 本项目示例 |
|---|---|---|---|
| 客户端入口 | Spring Cloud Gateway | 鉴权、路由、统一入口 | `/device/**` 转发 `share-device` |
| 服务同步调用 | OpenFeign + Nacos | 立即需要返回结果 | 订单服务查询计费规则 |
| 设备消息 | MQTT/EMQX | 柜机与云端双向通信 | 解锁指令、充电宝插入/弹出 |
| 业务事件 | RabbitMQ | 服务解耦、削峰、重试 | 借出建单、归还结算、支付成功 |
| 登录状态/幂等 | Redis | 短期状态和原子 set-if-absent | Token、`messageNo` 去重 |
| 业务主数据 | MySQL | 事务型数据 | 用户、设备、订单、支付 |
| 地理位置 | MongoDB | GeoJSON 半径查询 | 50km 内站点 |

**为什么不能只用一种通道：** 查询用户/规则时调用方必须马上得到结果，适合同步 Feign；硬件事件可能延迟、重复或离线，适合 MQTT；创建订单不应阻塞设备回调，适合 RabbitMQ。三者的失败语义不同，测试也必须分开。

## 2. 服务与端口

| 服务 | 端口 | 应用名 | 数据/外部依赖 |
|---|---:|---|---|
| gateway | 18080 | `share-gateway` | Nacos、Redis |
| auth | 9200 | `share-auth` | Redis、share-user/share-system |
| system | 9201 | `share-system` | MySQL、Redis |
| device | 9205 | `share-device` | MySQL、MongoDB、Redis、EMQX、Rabbit、user/order/rule |
| order | 19207 | `share-order` | MySQL、Redis、Rabbit、user/rule |
| rule | 19208 | `share-rule` | MySQL、Drools |
| user | 9209 | `share-user` | MySQL、微信小程序 API |
| payment | 9211 | `share-payment` | MySQL、Rabbit、微信支付、user/order |
| stastics | 9505 | `share-stastics` | user/order、外部 AI 8899（原样实现） |

`stastics` 是仓库原有拼写，复写第一遍最好保持 artifactId 和 service name 一致，避免路由/Feign 名称不匹配；后续再做重命名迁移。

> **配置陷阱：** `share-modules/share-stastics/src/main/resources/bootstrap.yml1` 的扩展名不是 `.yml`，Spring Boot 不会把它当作 bootstrap 配置加载；同目录的 `application.yml` 才是当前有效本地配置。复写时应改名为 `bootstrap.yml`，并统一所有服务的 Nacos namespace。

## 3. 准备开发环境

建议版本：

- JDK 17；
- Maven 3.8+；
- Node.js 18 或 20；
- MySQL 8；
- Redis 6/7；
- Nacos 2.x；
- RabbitMQ 3.x，安装 delayed-message exchange 插件；
- MongoDB 6/7；
- EMQX 5.x，或开发期用 Mosquitto 替代 Broker；
- 微信开发者工具和小程序测试账号；
- 一个公网 HTTPS 回调地址用于真实微信支付。

### 为什么先启动基础设施

Spring Boot 在创建数据源、MQ 回调、MongoTemplate 或微信支付证书 Bean 时就可能失败。先验证依赖服务端口，能把“Java 代码错误”和“基础设施不可达”分开。

### 基础设施烟雾测试

以下命令只是示例，应替换密码：

```bash
mysqladmin -h 127.0.0.1 -P 3306 -u root -p ping
redis-cli -h 127.0.0.1 -p 6379 ping
curl -fsS http://127.0.0.1:8848/nacos/
rabbitmq-diagnostics -q ping
mongosh --quiet --eval 'db.runCommand({ ping: 1 }).ok'
```

预期：MySQL `mysqld is alive`、Redis `PONG`、Nacos 返回页面、Rabbit 为 Ping succeeded、Mongo 为 `1`。

## 4. Nacos 配置为什么是启动关键

各服务本地 `bootstrap.yml` 只包含：应用名、profile、Nacos 地址、共享 Data ID。Spring 会继续从 Nacos 读取：

1. `application-dev.yml`：共享 Redis、日志、Swagger、Feign、MyBatis 等配置。
2. `${spring.application.name}-dev.yml`：服务自己的数据源、Mongo、Rabbit、微信、EMQX 等配置。

这些 Data ID 没有提交到仓库，因此必须自己创建。

### 4.1 不要复制作者 IP

把每个 `bootstrap.yml` 的 Nacos 地址改成自己的环境变量或本机地址，例如：

```yaml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:127.0.0.1:8848}
        namespace: ${NACOS_NAMESPACE:}
      config:
        server-addr: ${NACOS_ADDR:127.0.0.1:8848}
        namespace: ${NACOS_NAMESPACE:}
        file-extension: yml
        shared-configs:
          - application-${spring.profiles.active}.yml
```

**为什么 discovery 和 config 必须同 namespace：** 一个控制服务注册，一个控制配置读取。`main` 的 payment 开启 namespace，但 order/rule/gateway 注释 namespace，可能造成服务互相看不见。

### 4.2 共享配置模板

下面是依据代码所需键整理的最小学习配置，不是 `main` 中存在的文件：

```yaml
# Nacos Data ID: application-dev.yml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      password: ${REDIS_PASSWORD:}
      database: 0
  rabbitmq:
    host: 127.0.0.1
    port: 5672
    username: ${RABBIT_USER:guest}
    password: ${RABBIT_PASSWORD:guest}
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true
    listener:
      simple:
        acknowledge-mode: manual
        prefetch: 1

mybatis-plus:
  mapper-locations: classpath*:mapper/**/*Mapper.xml
  type-aliases-package: com.share.**.domain
  global-config:
    db-config:
      logic-delete-value: 1
      logic-not-delete-value: 0

security:
  ignore:
    whites:
      - /auth/login
      - /auth/h5/login/**
      - /code
      - /payment/wxPay/notify
```

实际白名单属性前缀需以 `IgnoreWhiteProperties` 为准。若沿用若依原配置，通常是 `security.ignore.whites`。

### 4.3 数据库服务配置模板

每个需要 MySQL 的服务使用独立库最清晰；学习期也可共用一库，但表名必须唯一：

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/share_device?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:}
```

分别创建 `share_user`、`share_rule`、`share_device`、`share_order`、`share_payment`。如果共用一库，远程服务边界仍然存在，不要让一个服务直接读另一个服务的 Mapper。

## 5. 网关路由

管理端和小程序请求路径都带服务前缀，例如 `/device/station/list`；Controller 实际路径是 `/station/list`，因此路由要 `StripPrefix=1`。

```yaml
# Nacos: share-gateway-dev.yml 的核心示例
spring:
  cloud:
    gateway:
      routes:
        - id: share-auth
          uri: lb://share-auth
          predicates:
            - Path=/auth/**
          filters:
            - StripPrefix=1
        - id: share-user
          uri: lb://share-user
          predicates:
            - Path=/user/**
          filters:
            - StripPrefix=1
        - id: share-device
          uri: lb://share-device
          predicates:
            - Path=/device/**
          filters:
            - StripPrefix=1
        - id: share-rule
          uri: lb://share-rule
          predicates:
            - Path=/rule/**
          filters:
            - StripPrefix=1
        - id: share-order
          uri: lb://share-order
          predicates:
            - Path=/order/**
          filters:
            - StripPrefix=1
        - id: share-payment
          uri: lb://share-payment
          predicates:
            - Path=/payment/**
          filters:
            - StripPrefix=1
```

统计模块的 Controller 本身已经是 `/sta`，而前端也请求 `/sta/*`。可以为它保留前缀：

```yaml
        - id: share-stastics
          uri: lb://share-stastics
          predicates:
            - Path=/sta/**
```

### 路由测试

启动 gateway 与对应服务后：

```bash
curl -i http://localhost:18080/user/actuator/health
curl -i http://localhost:18080/rule/actuator/health
```

如果 actuator 未开放，可加一个仅用于本地的 `/ping`。成功标准是 gateway 返回下游响应，而不是 404、503 或 `No servers available`。

## 6. JWT 到服务内用户上下文

调用链：

1. `TokenService.createToken` 生成随机 login token，把完整 `LoginUser` 写到 Redis。
2. JWT 只携带 login token、userId、username。
3. `AuthFilter` 解析 JWT，确认 Redis key 存在。
4. 网关把 user key/id/name 写入内部请求头，并删除客户端伪造的 `from-source`。
5. 服务内 `HeaderInterceptor` 把请求头写进 `SecurityContextHolder`，并刷新临近过期的 Redis 登录态。
6. `FeignRequestInterceptor` 把用户头和 Authorization 继续传到下一服务。
7. 请求完成后清理 ThreadLocal。

**为什么 JWT 和 Redis 同时存在：** JWT 负责不可篡改的身份声明，Redis 让服务端能主动登出、禁用会话和滑动续期。只验证 JWT 会导致已退出 Token 在过期前仍可用。

### 鉴权测试

```bash
# 1. 无 token
curl -i "$GATEWAY/user/userInfo/getLoginUserInfo"
# 预期 401

# 2. 伪造 token
curl -i "$GATEWAY/user/userInfo/getLoginUserInfo" \
  -H 'Authorization: Bearer invalid'
# 预期 401

# 3. 有效 token
curl -i "$GATEWAY/user/userInfo/getLoginUserInfo" \
  -H "Authorization: Bearer $TOKEN"
# 预期业务 code=200

# 4. 删除 Redis 登录 key 后重试
# 预期 401 登录状态已过期
```

## 7. MongoDB 地理位置初始化

`StationLocation.location` 使用 `GeoJsonPoint`。GeoJSON 坐标顺序必须是经度在前、纬度在后：

```javascript
use share_device
db.stationLocation.createIndex({ location: "2dsphere" })
db.stationLocation.findOne()
```

样例：

```json
{
  "stationId": 1,
  "location": {
    "type": "Point",
    "coordinates": [116.307503, 39.984104]
  }
}
```

### Geo 测试

写入两个已知坐标，一个在 1km 内，一个在 100km 外；调用 50km 查询，预期只返回近点。不要只检查“结果非空”，否则经纬度写反也可能偶然通过。

## 8. RabbitMQ delayed exchange

`RabbitService.sendDealyMessage` 设置 `x-delay`，`DelayExchangeConfig` 使用 `x-delayed-message`。Broker 必须安装插件：

```bash
rabbitmq-plugins enable rabbitmq_delayed_message_exchange
rabbitmq-plugins list -e
```

成功标准：列表中插件为 `[E*]`，服务声明延迟交换机不报 `unknown exchange type 'x-delayed-message'`。

普通订单事件并不依赖 delayed 插件，但设备“锁槽超时释放”的正确实现会需要它。

## 9. EMQX/MQTT 配置

`share-device` 需要以下键：

```yaml
emqx:
  client:
    client-id: share-device-${HOSTNAME:local}
    username: ${MQTT_USER:share-device}
    password: ${MQTT_PASSWORD:}
    server-uri: tcp://127.0.0.1:1883
    keep-alive-interval: 60
    connection-timeout: 10
```

这里配合推荐实现把 Java 字段从 `serverURI` 改为 `serverUri`；Spring 对全大写 acronym 的 relaxed binding 容易产生难读的 `server-u-r-i`，统一成 `server-uri` 更清楚。

`main` 的 `EmqxClientWrapper.init()` 没有调用 `connect()`。若原样复刻，扫码发布时客户端未连接，异常被吞掉，接口仍返回“成功”。推荐开发配置显式控制：

```yaml
emqx:
  enabled: true
```

应用启动时连接失败应 fail fast，或返回明确的设备服务不可用，不能伪造成功。

### MQTT 烟雾测试

终端 A：

```bash
mosquitto_sub -h 127.0.0.1 -t '/sys/#' -v
```

终端 B：

```bash
mosquitto_pub -h 127.0.0.1 \
  -t '/sys/powerBank/unlock' \
  -m 'mNo=test-001|cNo=C001|pNo=P001|sNo=01|uId=1' \
  -q 2
```

成功标准：服务日志能解析出 5 个字段，并只由 unlock handler 处理。

## 10. 微信配置

### 小程序登录

```yaml
wx:
  miniapp:
    app-id: ${WX_MINIAPP_APP_ID}
    secret: ${WX_MINIAPP_SECRET}
```

### 微信支付 V3

```yaml
wx:
  v3pay:
    appid: ${WX_MINIAPP_APP_ID}
    merchant-id: ${WX_MERCHANT_ID}
    private-key-path: ${WX_PRIVATE_KEY_PATH}
    merchant-serial-number: ${WX_MERCHANT_SERIAL}
    api-v3key: ${WX_API_V3_KEY}
    notify-url: ${WX_NOTIFY_URL}
```

私钥路径必须在运行机器上可读；私钥、API v3 key、secret 不进入 Git。支付回调必须是微信可访问的 HTTPS URL，不能是 localhost。

## 11. 基础设施阶段验收清单

- [ ] 所有服务使用同一个 Nacos namespace。
- [ ] gateway 能通过 `lb://` 找到 user/device/rule/order/payment。
- [ ] Redis 登录 key 可写、可过期。
- [ ] Rabbit producer confirm、return、manual ack 配置生效。
- [ ] MongoDB 有 `2dsphere` 索引，坐标顺序正确。
- [ ] MQTT 发布与订阅都能看到测试消息。
- [ ] 微信机密只从环境变量/Secret Manager 读取。
- [ ] MySQL 时区、字符集、逻辑删除默认值一致。

> **重难点：** Nacos 配置是代码的一部分，只是存放位置在仓库外。应在自己的项目中维护脱敏模板和配置字段说明，否则换一台机器就无法复现。

> **注意：** 本文配置是根据源码依赖推导的学习模板，不是从 `main` 找到的原文件。真实部署要结合你自己的 Nacos、数据库和安全策略调整。
