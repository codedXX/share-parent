# share-user 注册到 Nacos 的机制

## 一、整体流程概览

`share-user` 是一个标准的 Spring Boot + Spring Cloud Alibaba 应用，注册到 Nacos 的过程由 **依赖引入 + 自动装配 + bootstrap 配置** 三者协同完成，无需手写注册代码。

注册链路一句话概括：

```
引入 nacos-discovery starter
        ↓
Spring Boot 启动触发 WebServerInitializedEvent
        ↓
NacosAutoServiceRegistration 监听事件
        ↓
NacosServiceRegistry.register() 向 Nacos 发 HTTP POST
        ↓
Nacos 注册中心新增实例 (服务名=share-user, 端口=9209)
```

---

## 二、依赖引入（pom.xml）

`share-modules/share-user/pom.xml:22-31`：

```xml
<!-- SpringCloud Alibaba Nacos -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>

<!-- SpringCloud Alibaba Nacos Config -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
```

- `spring-cloud-starter-alibaba-nacos-discovery`：服务注册与发现 starter，引入后自动激活 `NacosDiscoveryAutoConfiguration`，自动装配出 `NacosServiceRegistry`、`NacosRegistration`、`NacosAutoServiceRegistration` 三个核心 Bean。
- `spring-cloud-starter-alibaba-nacos-config`：配置中心 starter，与注册是两个独立功能，本问只讲 discovery 部分。

---

## 三、启动类（ShareUserApplication.java）

`share-modules/share-user/src/main/java/com/share/user/ShareUserApplication.java:14-18`：

```java
@EnableCustomConfig
@EnableRyFeignClients
@SpringBootApplication
@MapperScan("com.share.user.mapper")
public class ShareUserApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShareUserApplication.class, args);
    }
}
```

注意几个点：

| 注解 | 与 Nacos 注册的关系 |
|------|---------------------|
| `@SpringBootApplication` | 触发 Spring Boot 自动装配，扫描 `spring-cloud-starter-alibaba-nacos-discovery` 里的自动配置类 |
| `@EnableRyFeignClients` | 与**注册无关**，只是开启 Feign 客户端扫描（作为消费方用），share-user 删掉它照样能注册 |
| `@EnableCustomConfig` | 与**注册无关**，是若依自定义的安全/上下文配置 |

**关键结论**：share-user 注册到 Nacos **不需要任何专门的注解**，`@SpringBootApplication` 加上 nacos-discovery 依赖 + bootstrap.yml 配置就足够了。自动装配会自动完成注册。

---

## 四、bootstrap.yml 配置

`share-modules/share-user/src/main/resources/bootstrap.yml`：

```yaml
server:
  port: 9209                     # ← 注册到 Nacos 的端口

spring:
  application:
    name: share-user              # ← 注册到 Nacos 的服务名
  profiles:
    active: dev
  cloud:
    nacos:
      discovery:
        # 服务注册地址
        server-addr: localhost:8848
#        namespace:  d707b2cd-8895-445d-acc6-d229b4ccb096
      config:
        server-addr: localhost:8848
        file-extension: yml
        shared-configs:
          - application-${spring.profiles.active}.${spring.cloud.nacos.config.file-extension}
```

注册时用到的三个核心字段：

| 配置项 | 值 | 作用 |
|--------|----|----|
| `spring.application.name` | `share-user` | **服务名**，注册到 Nacos 后的唯一标识，消费方 `@FeignClient(value="share-user")` 就是靠它匹配 |
| `server.port` | `9209` | **实例端口**，注册到 Nacos 的实例端口 |
| `spring.cloud.nacos.discovery.server-addr` | `localhost:8848` | **Nacos 地址**，注册请求发到哪 |
| `spring.cloud.nacos.discovery.namespace` | （注释掉了）| 命名空间，注释掉表示用默认 `public` |

> 注意：`namespace` 被注释，所以注册到的是 **默认命名空间 `public`**。若要隔离 dev/test/prod，取消注释配上对应命名空间 ID 即可，但消费方也必须配同一 namespace 才能发现服务。

---

## 五、自动注册的底层机制（Spring Cloud Alibaba）

这是整个注册的"自动"部分，不需要写任何代码：

### 1. 自动装配类

`spring-cloud-starter-alibaba-nacos-discovery` 通过 `spring.factories` / `AutoConfiguration.imports` 注册了：

- `NacosDiscoveryAutoConfiguration`：创建 `NacosServiceRegistry`、`NacosRegistration`、`NacosAutoServiceRegistration` 三个 Bean。
- `NacosDiscoveryClientConfiguration`：创建 `NacosDiscoveryClient`（消费方拉取服务列表用）。

### 2. 三个核心 Bean 的职责

| Bean | 职责 |
|------|------|
| `NacosServiceRegistry` | 实现 `ServiceRegistry` 接口，`register()` 方法向 Nacos 发 HTTP 请求 |
| `NacosRegistration` | 封装当前服务实例信息（服务名、IP、端口、metadata 等） |
| `NacosAutoServiceRegistration` | 继承 `AbstractAutoServiceRegistration`，监听 Spring 容器事件触发注册 |

### 3. 触发时机

`NacosAutoServiceRegistration` 继承自 Spring Cloud 的 `AbstractAutoServiceRegistration`，后者实现了 `SmartLifecycle` 和 `ApplicationListener<WebServerInitializedEvent>`。

注册触发链：

```
Tomcat 启动完成
    ↓
发布 WebServerInitializedEvent
    ↓
AbstractAutoServiceRegistration.onApplicationEvent() 接收事件
    ↓
调用 NacosServiceRegistry.register(NacosRegistration)
    ↓
组装实例信息（服务名 share-user, IP, 端口 9209, 心跳间隔等）
    ↓
向 Nacos 发送 POST http://localhost:8848/nacos/v1/ns/instance
    ↓
Nacos 注册中心新增实例
```

### 4. 心跳保活

注册后，Nacos 客户端（`NamingService`）会**周期性**向 Nacos 发送心跳（默认 5 秒一次），告诉 Nacos "我还活着"。如果 Nacos 长时间（默认 15 秒未收到心跳标记不健康，30 秒摘除）收不到心跳，会把实例标记为不健康或剔除。

---

## 六、注册到 Nacos 后能看到什么

启动 `ShareUserApplication` 后，访问 Nacos 控制台 `http://localhost:8848/nacos`（默认账号 nacos/nacos）：

1. 进入 **服务管理 → 服务列表**。
2. 命名空间选 `public`（因为 namespace 注释掉了）。
3. 能看到一条服务名 `share-user` 的记录。
4. 点进详情能看到实例：IP=`本机IP`，端口=`9209`，健康状态=`true`。

也可以通过 Open API 验证：

```
GET http://localhost:8848/nacos/v1/ns/instance/list?serviceName=share-user
```

返回的 JSON 里 `hosts` 数组就是注册上来的实例。

---

## 七、注册相关配置项速查

`spring.cloud.nacos.discovery` 下常用的注册配置（本项目的 bootstrap.yml 只用了 `server-addr`）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server-addr` | 无（必填）| Nacos 服务地址，格式 `ip:port` |
| `namespace` | `public` | 命名空间 ID，用于环境隔离 |
| `group` | `DEFAULT_GROUP` | 分组名 |
| `cluster-name` | `DEFAULT` | 集群名 |
| `ip` | 自动获取本机 IP | 注册的 IP，多网卡时可手动指定 |
| `port` | 取 `server.port` | 注册的端口 |
| `metadata` | 空 | 自定义元数据 |
| `ephemeral` | `true` | 是否临时实例（临时实例靠心跳保活，永久实例由 Nacos 主动探测）|
| `heart-beat-interval` | `5000`（ms）| 心跳发送间隔 |
| `heart-beat-timeout` | `15000`（ms）| 心跳超时标记不健康 |
| `ip-delete-timeout` | `30000`（ms）| 超时摘除实例 |

---

## 八、总结

`share-user` 注册到 Nacos 的关键就三点：

1. **依赖**：`pom.xml` 引入 `spring-cloud-starter-alibaba-nacos-discovery`。
2. **配置**：`bootstrap.yml` 配 `spring.application.name=share-user` 和 `spring.cloud.nacos.discovery.server-addr=localhost:8848`。
3. **自动装配**：Spring Boot 启动时由 `NacosAutoServiceRegistration` 监听 `WebServerInitializedEvent`，自动调用 `NacosServiceRegistry.register()` 把实例（服务名 `share-user`，端口 `9209`）注册到 `localhost:8848` 的 `public` 命名空间。

**不需要任何 `@EnableNacosXxx` 注解，也不需要手写注册代码**，这就是 Spring Cloud Alibaba 自动装配的设计目标。消费方（如 `share-auth`）通过 `@FeignClient(value="share-user")` 拿着同一个服务名 `share-user` 去 Nacos 查实例，就能完成远程调用--这套机制支撑了整个微服务集群的服务发现。
