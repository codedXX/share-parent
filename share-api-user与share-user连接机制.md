# share-api-user ↔ share-user 的连接机制

## 一、两个模块的角色

| 模块 | 角色 | 说明 |
|------|------|------|
| `share-api-user` | **API 契约模块**（纯接口 jar，不启动服务） | 只定义 Feign 接口、DTO、降级工厂，供别的服务依赖 |
| `share-modules/share-user` | **服务提供者**（Spring Boot 应用） | 真正实现业务逻辑，注册到 Nacos |

注意：`share-user` 自己并不依赖 `share-api-user`，它只是被调用的目标。真正依赖 `share-api-user` 的是调用方，比如 `share-auth` 里的 `H5LoginService`。

## 二、`@FeignClient` 这一行是连接核心

`share-api-user/src/main/java/com/share/user/api/RemoteUserService.java:16`：

```java
@FeignClient(contextId = "remoteUserInfoService",
        value = ServiceNameConstants.SHARE_USER,        // = "share-user"
        fallbackFactory = RemoteUserFallbackFactory.class)
public interface RemoteUserService {
    @GetMapping("/userInfo/wxLogin/{code}")
    R<UserInfo> wxLogin(@PathVariable("code") String code);

    @GetMapping("/userInfo/getUserInfo/{id}")
    R<UserInfo> getInfo(@PathVariable("id") Long id);

    @GetMapping("/userInfo/getUserCount")
    R getUserCount();
}
```

三个关键属性：

- **`value = ServiceNameConstants.SHARE_USER`**：常量定义在 `share-common-core` 的 `ServiceNameConstants.java:28`，值就是字符串 `"share-user"`。这个值是 **Nacos 注册中心里的服务名**，Feign 通过它做服务发现。
- **`contextId = "remoteUserInfoService"`**：给这个 Feign 客户端一个唯一 Bean 名，避免同一服务存在多个 `@FeignClient` 时 Bean 名冲突。
- **`fallbackFactory = RemoteUserFallbackFactory.class`**：调用失败时走这个降级工厂（`RemoteUserFallbackFactory.java:16`），它实现了 `FallbackFactory<RemoteUserService>`，在 `create(Throwable)` 里记录日志并抛 `ServiceException`。

## 三、服务名是怎么对上的

**提供方（share-user）** 在 `bootstrap.yml` 里把名字注册进 Nacos：

```yaml
spring:
  application:
    name: share-user        # ← 注册到 Nacos 的服务名
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
```

**消费方** 通过 `@FeignClient(value="share-user")` 拿这个名字去 Nacos 拉到 share-user 实例列表（IP+端口 9209），再做负载均衡选一个实例发 HTTP 请求。两边靠字符串 `"share-user"` 对上，不需要硬编码 IP。

## 四、Feign 代理是怎么被扫描出来的

消费方启动类（如 `share-auth`）加的是自定义注解 `@EnableRyFeignClients`，定义在 `share-common-security/.../EnableRyFeignClients.java:15`：

```java
@EnableFeignClients   // ← 元注解，组合了 Spring Cloud 原生注解
public @interface EnableRyFeignClients {
    String[] basePackages() default { "com.share" };
    ...
}
```

它通过 `@EnableFeignClients` 元注解 + `basePackages = "com.share"`，让 Spring 扫描整个 `com.share` 包下所有 `@FeignClient` 接口（包括 `com.share.user.api.RemoteUserService`），为每个接口生成动态代理 Bean。所以 `H5LoginService.java:19` 才能直接 `@Autowired private RemoteUserService remoteUserService;`。

## 五、接口路径与 Controller 是怎么对上的

Feign 接口方法上的 Spring MVC 注解（`@GetMapping`）会被 Feign 用来拼请求路径，必须和提供方 Controller 完全对应：

| Feign 接口（share-api-user） | 提供方 Controller（share-user 的 `UserInfoApiController`） |
|---|---|
| `@GetMapping("/userInfo/wxLogin/{code}")` | `@RequestMapping("/userInfo")` + `@GetMapping("/wxLogin/{code}")` |
| `@GetMapping("/userInfo/getUserInfo/{id}")` | 同 Controller 里 `@GetMapping("/{id}")` 等对应方法 |

`UserInfoApiController.java:28` 的类级 `@RequestMapping("/userInfo")` 把所有方法挂到 `/userInfo/**` 下，正好对上 Feign 接口里的 `/userInfo/xxx`。

## 六、一次完整调用链（以微信登录为例）

1. `share-auth` 的 `H5LoginService.login(code)` 调用 `remoteUserService.wxLogin(code)`。
2. Feign 代理查 Nacos：服务名 `share-user` -> 拿到实例（如 `localhost:9209`）。
3. 根据 `@GetMapping("/userInfo/wxLogin/{code}")` 拼出 `GET http://share-user/userInfo/wxLogin/{code}`（带 Spring Cloud LoadBalancer 负载均衡）。
4. 请求打到 `share-user` 的 `UserInfoApiController.wxLogin()`（`UserInfoApiController.java:76`）。
5. 若调用抛异常/超时 -> 走 `RemoteUserFallbackFactory.create()` 降级。

## 七、依赖关系小结

```
share-auth  ──依赖──>  share-api-user  ──依赖──>  share-common-core
   (调用方)               (Feign 接口契约)              (ServiceNameConstants)
                                                ^
share-modules/share-user  ──注册服务名 "share-user"──>  Nacos
   (提供方)
```

`share-api-user` 只是个"接口声明层"，它本身不发起也不接收请求；它通过 `@FeignClient(value="share-user")` 在**运行时**借 Nacos 找到 `share-user` 服务的真实实例，再由 Feign 动态代理完成远程调用。`@EnableRyFeignClients` 负责把接口扫描成 Bean，`fallbackFactory` 负责降级，MVC 注解负责定义路径契约——这套就是若依风格微服务里 API 模块与服务模块解耦耦合的标准做法。

---

## 八、为什么 ShareAuthApplication 和 ShareUserApplication 都要加 `@EnableRyFeignClients`

先看两个模块的实际情况：

- `share-auth` 内部确实注入了 `RemoteUserService`（`H5LoginService.java:19`、`SysLoginService.java:30`），是真正的**消费方**。
- `share-user` 全模块 grep 不到任何 `@FeignClient` 或 `RemoteService` 注入，它是**纯提供方**。

### 1. `ShareAuthApplication` -- 必须加（真消费方）

`share-auth` 要远程调用 `share-user`、`share-system` 等服务：
- 依赖了 `share-api-user`、`share-api-system` 这些 API 契约 jar；
- `H5LoginService` 里 `@Autowired RemoteUserService`；
- `SysLoginService` 里也注入了 `RemoteUserService`（系统服务那个）。

不加 `@EnableRyFeignClients`，Spring 不会扫描 `com.share` 包下的 `@FeignClient` 接口，启动时直接报 `NoSuchBeanDefinitionException`。所以它**不加就跑不起来**。

### 2. `ShareUserApplication` -- 约定/可扩展（非必需）

`share-user` 当前没有任何 Feign 调用，加这个注解其实是若依框架的**统一模板**：
- 所有业务模块启动类都套 `@EnableCustomConfig + @EnableRyFeignClients`，保持风格一致；
- **为将来扩展预留**：哪天 share-user 要调 share-system、share-file，直接 `@Autowired` 就能用，不用再回头改启动类；
- 加了不会有副作用（`com.share` 包下没有 `@FeignClient` 时，`@EnableFeignClients` 不会创建任何代理 Bean）。

换句话说，share-user 这个注解**严格来说可以删掉**，应用照样能启动并对外提供服务。它只是作为消费方身份的"占位符"。

### 3. 一句话总结

- `ShareAuthApplication` 加 `@EnableRyFeignClients`：**刚需**，因为它要扫描 Feign 接口去远程调别人。
- `ShareUserApplication` 加 `@EnableRyFeignClients`：**模板化/前瞻性**，当前不消费任何服务，留着是为了以后扩展方便，删了也不影响启动。

判定一个启动类是否**真正需要**这个注解，看它依赖的 `share-api-*` 模块里有没有 `@FeignClient` 接口被本模块 `@Autowired` 进来即可。

---

## 九、`bootstrap.yml` 里 Nacos 的 discovery 与 config 配置说明

对应 `share-modules/share-user/src/main/resources/bootstrap.yml` 中的：

```yaml
nacos:
  discovery:
    # 服务注册地址
    server-addr: localhost:8848
#    namespace:  d707b2cd-8895-445d-acc6-d229b4ccb096
  config:
    # 配置中心地址
    server-addr: localhost:8848
#    namespace:  d707b2cd-8895-445d-acc6-d229b4ccb096
    # 配置文件格式
    file-extension: yml
    # 共享配置
    shared-configs:
      - application-${spring.profiles.active}.${spring.cloud.nacos.config.file-extension}
```

这是 Spring Cloud Alibaba Nacos 的两块功能，都写在 `bootstrap.yml` 里（注意是 bootstrap 不是 application，因为它要在应用上下文初始化前就生效）。

### 1. `nacos.discovery` -- 服务注册与发现（注册中心）

作用：
- 启动时把 `share-user` 服务实例（IP + 端口 9209）**注册**到 Nacos（地址 `localhost:8848`）。
- 同时作为消费方时，也能从 Nacos **拉取**其他服务（如 `share-auth`）的实例列表做负载均衡。
- 前面讲的 `@FeignClient(value="share-user")` 能找到实例，就是靠这块把 `share-user` 注册上去的。

> `namespace` 被注释了，表示用**默认命名空间 `public`**。命名空间一般用来隔离 dev/test/prod 环境，配上后只能发现同一命名空间里的服务。

### 2. `nacos.config` -- 配置中心

作用：把配置从本地 `application.yml` 搬到 Nacos 上集中管理，应用启动时**从 Nacos 拉配置**下来。

拉取规则（Spring Cloud Alibaba Nacos Config 默认行为）：
1. **主配置**：默认会拉 `share-user-dev.yml`（= `${spring.application.name}` + `-` + `${profile}` + `.` + `file-extension`）。即服务名 `share-user` + 当前激活的 profile `dev` + 格式 `yml`。
2. **共享配置** `shared-configs`：额外拉一份 `application-dev.yml`，这是所有服务共用的配置（如公共数据库、Redis、日志等），改一处全局生效。

`file-extension: yml` 告诉 Nacos 配置内容按 YAML 解析，默认是 `properties`。

### 3. 为什么放在 `bootstrap.yml` 而不是 `application.yml`

bootstrap 阶段比 application 早，Spring Cloud 需要先从 Nacos **拉到远程配置**，才能继续构建 ApplicationContext（比如远程配置里可能有数据源、Redis 等 Bean 定义）。如果放 application.yml，远程配置就来不及加载了。

### 4. 两块配置的关系

它们都用同一个 Nacos 服务（`localhost:8848`），但职责不同：

| 配置项 | 职责 | 解决什么问题 |
|--------|------|--------------|
| `discovery` | 注册中心 | 服务在哪、谁能调谁（Feign 服务发现） |
| `config` | 配置中心 | 配置从哪来、动态刷新 |

一句话：`discovery` 让**别人能找到 share-user**，`config` 让 **share-user 启动时拿到集中管理的配置**。两者共同支撑了前面讲的 Feign 调用链。
