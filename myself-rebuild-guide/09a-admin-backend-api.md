# 09A. `share-ui` 后台管理接口

> 本章的目标是：在 `myself` 上为 `share-ui` 的业务管理页面提供可鉴权、可分页、可测试的 HTTP 接口。它不要求你重写若依已经保留的 `/system/**` 接口；要复写的是共享充电宝业务接口：设备、费用规则、会员、订单和固定统计。

> **先说明基准。** `main` 已经实现了部分 Controller，但它们存在三类问题：柜机/站点/充电宝/费用规则 Controller 多数没有权限注解；费用规则 `PUT` 只返回成功、不落库；订单页请求的 `/order/orderInfo/**` 在 `main` 中没有管理 Controller。本章标为 **MAIN 原样** 的内容用于核对代码，标为 **推荐实现** 的内容才是应该在 `myself` 练习时提交的版本。

## 1. 边界、调用链和完成顺序

`share-ui` 是浏览器前端，不是 Java 服务。不要在前端项目中实现 Controller，也不要把所有业务 Controller 塞进 `share-system`。每个接口归属拥有该领域数据和事务的服务：

```text
share-ui/src/api/*
  -> /dev-api (Vite 开发代理)
  -> share-gateway (按 /device、/user、/rule、/order、/sta 路由)
  -> 领域服务的 Controller
  -> Service (业务校验、事务、状态机)
  -> Mapper / Mongo Repository / Feign
```

| 业务页面 | 服务 | Controller 根路径（服务内） | 网关后的前端路径 | 本章状态 |
|---|---|---|---|---|
| 柜机类型、柜机、充电宝、站点、插槽、地区、地图 | `share-device` | `/cabinetType`、`/cabinet`、`/powerBank`、`/station` 等 | `/device/**` | CRUD 已有参考，需补鉴权和校验 |
| 费用规则 | `share-rule` | `/feeRule` | `/rule/feeRule/**` | `PUT` 必须修复为真实更新 |
| 会员 | `share-user` | `/userInfo` | `/user/userInfo/**` | 已有参考，需限制敏感字段/禁止改绑 |
| 后台订单、固定报表 | `share-order` | **新建** `/orderInfo` | `/order/orderInfo/**` | `main` 的真实缺口，必须实现 |
| AI/实验性统计 | `share-stastics` | `/order`、`/user`（先以实际 Controller 为准） | `/sta/**` | 不接受浏览器传入 SQL |
| 管理员、角色、菜单、字典、在线用户 | `share-system` | `/system/**` | `/system/**` | `myself` 原有底座，勿重复写 |

推荐的提交顺序如下。一个提交只解决一件能单独验证的事，发生问题才容易回退和定位：

1. 修正 Maven 标准目录，先让空服务和一个最小测试被 Maven 识别。
2. 为设备、规则、会员已有 CRUD 加统一权限、参数校验和 Service 层约束。
3. 实现订单只读管理接口和固定统计接口。
4. 补菜单 SQL、角色权限和前后端契约测试。
5. 最后接 Nacos、MySQL、Redis、MongoDB 和真实网关做集成测试。

## 2. 第零步：先修正 `myself` 的源码目录

当前练习分支已经出现 `share-modules/share-device/src/java` 与 `src/resources`。这是 **非 Maven 标准目录**；Maven 默认只编译 `src/main/java`，只复制 `src/main/resources`。如果代码放在前者，IDE 可能显示文件存在，但 `mvn compile` 不会编译它，资源也不会进入 jar。

目录必须是：

```text
share-modules/share-device/
  pom.xml
  src/main/java/com/share/device/ShareDeviceApplication.java
  src/main/java/com/share/device/controller/...
  src/main/resources/bootstrap.yml
  src/main/resources/mapper/device/...
  src/test/java/com/share/device/controller/...
```

不要通过在 `pom.xml` 里设置自定义 `sourceDirectory` 来迁就 `src/java`。这会让所有模块的布局不一致，也会让 IDE、打包插件和后续读代码的人不断踩坑。把文件移动到标准路径后执行：

```powershell
mvn -pl share-modules/share-device -am -DskipTests compile
```

成功标准是编译输出中出现 `share-device`，并且 `target/classes/com/share/device/ShareDeviceApplication.class` 存在。

## 3. 第一步：依赖、测试依赖和模块边界

### 3.1 生产依赖

`share-modules/pom.xml` 已通过父模块引入 `share-common-security`。每个业务服务自己的 `pom.xml` 应只声明它实际使用的依赖。以 `share-device` 为例，`main` 的生产依赖包括：Nacos discovery/config、Sentinel、Actuator、MySQL、`share-common-datascope`、`share-common-log`、MongoDB、MQTT、业务 Feign API 和 `share-common-rabbit`。

先恢复最小可编译的服务依赖，再按功能追加可选依赖。下面片段放在各领域服务自己的 `<dependencies>` 中；版本全部由根 `pom.xml` 的 Spring Boot/Spring Cloud BOM 和 `share.version` 管理，不要在子模块重复写版本：

```xml
<!-- 所有后台业务服务：读取网关传入的登录人、权限、BaseController -->
<dependency>
    <groupId>com.share</groupId>
    <artifactId>share-common-security</artifactId>
</dependency>
<dependency>
    <groupId>com.share</groupId>
    <artifactId>share-common-log</artifactId>
</dependency>

<!-- 使用 MyBatis-Plus / PageHelper 查询业务表的服务 -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
</dependency>
<dependency>
    <groupId>com.share</groupId>
    <artifactId>share-common-datascope</artifactId>
</dependency>

<!-- 只有 share-device 才需要：站点地理位置和柜机协议 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
<dependency>
    <groupId>org.eclipse.paho</groupId>
    <artifactId>org.eclipse.paho.client.mqttv3</artifactId>
</dependency>

<!-- 只有跨服务读规则/会员/订单时才添加相应契约，不能依赖对方实现模块 -->
<dependency>
    <groupId>com.share</groupId>
    <artifactId>share-api-rule</artifactId>
</dependency>
<dependency>
    <groupId>com.share</groupId>
    <artifactId>share-api-user</artifactId>
</dependency>
```

`share-device` 的借还事件还需要 `share-api-order` 与 `share-common-rabbit`；`share-order` 的结算逻辑需要 `share-api-rule`、`share-api-user` 和 `share-common-rabbit`。这些是异步业务流程的依赖，不是为了后台页面才添加。后台订单只读查询如果只查本地订单和账单表，不需要为了列表接口额外引入 Feign；详情需要会员摘要时才使用 `share-api-user`，并映射成脱敏 DTO。

**不要为了写管理接口而额外引入 `share-system`。** 管理员身份由网关令牌和 `share-common-security` 传入；业务服务用 `@RequiresPermissions` 校验权限码即可。只有确实需要调用用户、订单、规则服务时，才通过对应的 `share-api-*` Feign 契约依赖调用，不能直接依赖另一个服务的实现模块。

### 3.2 给每个改造服务加入测试依赖

`main` 的 `share-device`、`share-user` 和 `share-rule` 没有完整的测试依赖；`share-order` 虽已有 `spring-boot-starter-test`，仍没有可用的管理接口测试。请在每个要写测试的服务 `pom.xml` 的 `<dependencies>` 末尾加入：

```xml
<!-- 只在测试编译和测试运行期使用；版本由 Spring Boot BOM 管理 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

`spring-boot-starter-test` 已带 JUnit Jupiter、Mockito、AssertJ 和 Spring Test。先使用纯 Mockito 的 Service 单元测试，不要在每个测试中启动 Nacos、MQTT、Redis 或 MongoDB。Controller 的 HTTP 契约用 `@WebMvcTest` + `MockMvc`；少数需要 MyBatis、分页插件和真实 SQL 的测试再使用独立测试数据库。

若项目的编译器未自动选择 JUnit 5，补充 Surefire（仅当实际执行测试时提示 “No tests were executed” 才加，避免无意义的 POM 改动）：

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-surefire-plugin</artifactId>
      <version>3.2.5</version>
    </plugin>
  </plugins>
</build>
```

### 3.3 不能靠前端隐藏按钮做鉴权

`v-hasPermi` 只影响按钮是否可见。攻击者仍可在浏览器、curl 或代理工具中直接请求 `/device/cabinet`。每一个管理动作必须在 **Controller 方法** 上声明权限。当前 `PreAuthorizeAspect` 使用 `method.getAnnotation(...)` 读取注解，类级 `@RequiresPermissions` 在这个实现中不会被切面识别；因此不要只写在类上。

统一采用以下命名，前端、菜单 SQL、角色权限和后端注解必须逐字相同：

| 资源 | list | query | add | edit | remove | export |
|---|---|---|---|---|---|---|
| 柜机类型 | `device:cabinetType:list` | `device:cabinetType:query` | `device:cabinetType:add` | `device:cabinetType:edit` | `device:cabinetType:remove` | `device:cabinetType:export` |
| 柜机 | `device:cabinet:list` | `device:cabinet:query` | `device:cabinet:add` | `device:cabinet:edit` | `device:cabinet:remove` | `device:cabinet:export` |
| 充电宝 | `device:powerBank:list` | `device:powerBank:query` | `device:powerBank:add` | `device:powerBank:edit` | `device:powerBank:remove` | `device:powerBank:export` |
| 站点 | `device:station:list` | `device:station:query` | `device:station:add` | `device:station:edit` | `device:station:remove` | `device:station:export` |
| 费用规则 | `rule:feeRule:list` | `rule:feeRule:query` | `rule:feeRule:add` | `rule:feeRule:edit` | `rule:feeRule:remove` | `rule:feeRule:export` |
| 会员 | `user:userInfo:list` | `user:userInfo:query` | `user:userInfo:add` | `user:userInfo:edit` | `user:userInfo:remove` | `user:userInfo:export` |
| 订单 | `order:orderInfo:list` | `order:orderInfo:query` | 不提供 | 不提供 | 不提供 | `order:orderInfo:export` |
| 订单统计 | `order:statistics:query` | - | - | - | - | - |

其中 `list` 与 `query` 不要混用：列表分页使用 `list`，单条详情使用 `query`。这样只读角色可以被精确授权。

## 4. 第二步：统一 HTTP 契约

### 4.1 路径到底如何拼成 `/device/cabinet/list`

服务的 Controller 不写 `/device`。例如 `share-device` 中 `CabinetController` 的根路径是 `@RequestMapping("/cabinet")`；Nacos 网关把服务前缀 `/device` 转发到它，因此浏览器访问的是 `/device/cabinet/list`。相同规律如下：

```text
浏览器 /device/cabinet/list      -> share-device  /cabinet/list
浏览器 /rule/feeRule/list        -> share-rule    /feeRule/list
浏览器 /user/userInfo/list       -> share-user    /userInfo/list
浏览器 /order/orderInfo/list     -> share-order   /orderInfo/list
```

Controller 内若再加 `/device`，最终会变成 `/device/device/cabinet/list`，前端得到 404。先用网关路由和 `curl` 验证，再改 Vue。

### 4.2 响应格式与分页

继承 `BaseController`，不要手写与若依不兼容的 `Map`：

```java
@GetMapping("/list")
@RequiresPermissions("device:cabinet:list")
public TableDataInfo list(Cabinet query) {
    startPage();                 // 读取 pageNum、pageSize、orderByColumn、isAsc
    List<Cabinet> rows = cabinetService.selectListCabinet(query);
    return getDataTable(rows);   // { code: 200, msg, rows, total }
}

@GetMapping("/{id}")
@RequiresPermissions("device:cabinet:query")
public AjaxResult getInfo(@PathVariable Long id) {
    return success(cabinetService.getById(id)); // { code: 200, msg, data }
}
```

`startPage()` 必须在 Mapper 查询之前调用；漏掉它时前端仍收到 `rows`，但 `total` 是整个结果的数目，页面会一次拉全表。不要把 `PageHelper` 的线程变量带到异步线程；查询结束后没有特殊需要时由框架清理，复杂流程可显式调用 `clearPage()`。

写操作统一返回 `toAjax(rows)` 或 `toAjax(boolean)`。它们生成 `{ code: 200 }` 或 `{ code: 500 }`，符合 `share-ui/src/utils/request.js` 的统一处理。数据库记录不存在、违反状态机、存在外键引用等，应由 Service 抛出明确 `ServiceException`，不要返回“成功但影响行数为 0”。

### 4.3 参数校验与字段白名单

Controller 收到的 `@RequestBody` 不是可信对象。给新增/编辑请求专用 DTO 加 `jakarta.validation` 约束；不要直接让前端传完整实体后无选择 `updateById`。

```java
public record CabinetSaveRequest(
        @NotBlank(message = "柜机编号不能为空")
        @Pattern(regexp = "[A-Za-z0-9_-]{4,64}", message = "柜机编号格式错误")
        String cabinetNo,
        @NotNull(message = "柜机类型不能为空") Long cabinetTypeId,
        @NotNull(message = "站点不能为空") Long stationId) { }

@PostMapping
@RequiresPermissions("device:cabinet:add")
@Log(title = "柜机", businessType = BusinessType.INSERT)
public AjaxResult add(@Valid @RequestBody CabinetSaveRequest request) {
    return toAjax(cabinetService.createFromAdmin(request));
}
```

`@Valid` 是 `jakarta.validation.Valid`。若项目没有校验启动器，先检查依赖树；Spring Boot 的 Web starter 通常已间接提供 Hibernate Validator。不要允许管理端提交这些字段：`id`、`createBy`、`createTime`、`updateBy`、`updateTime`、库存派生计数、订单状态、微信 `openid`、支付单号。服务端从安全上下文或自己的业务规则生成它们。

## 5. 第三步：设备后台接口（`share-device`）

### 5.1 先按依赖顺序写，而不是按页面顺序写

正确顺序是：柜机类型 -> 充电宝 -> 柜机 -> 站点关联 -> 地图/地区。柜机引用类型和站点，站点又汇总柜机；倒着写会造成大量“先假设数据存在”的代码。

`main` 的 Controller 文件可作为位置参考：

```text
share-modules/share-device/src/main/java/com/share/device/controller/
  CabinetTypeController.java
  PowerBankController.java
  CabinetController.java
  StationController.java
  CabinetSlotController.java        # 若要单独给运维查看插槽
  RegionController.java
  MapController.java
  DeviceController.java             # 面向小程序/设备协议，不应混进后台 CRUD
```

把管理端 HTTP 层和设备协议层分开：`DeviceController` 的扫码、解锁、MQTT 回调属于用户/设备事件，不应获得后台任意 CRUD 权限；柜机配置则应由管理员权限保护。

### 5.2 为现有 CRUD 补齐鉴权和日志

`main` 中只有 `CabinetTypeController` 有较完整的 `@RequiresPermissions`；`CabinetController`、`PowerBankController`、`StationController` 基本裸露。下面以柜机为模板，其他三者逐项替换资源名：

```java
@Tag(name = "充电宝柜机接口管理")
@RestController
@RequestMapping("/cabinet")
public class CabinetController extends BaseController {

    private final ICabinetService cabinetService;

    public CabinetController(ICabinetService cabinetService) {
        this.cabinetService = cabinetService;
    }

    @GetMapping("/list")
    @RequiresPermissions("device:cabinet:list")
    public TableDataInfo list(Cabinet query) {
        startPage();
        return getDataTable(cabinetService.selectListCabinet(query));
    }

    @GetMapping("/{id}")
    @RequiresPermissions("device:cabinet:query")
    public AjaxResult getInfo(@PathVariable Long id) {
        return success(cabinetService.requireById(id));
    }

    @PostMapping
    @RequiresPermissions("device:cabinet:add")
    @Log(title = "柜机", businessType = BusinessType.INSERT)
    public AjaxResult add(@Valid @RequestBody CabinetSaveRequest request) {
        return toAjax(cabinetService.createFromAdmin(request));
    }

    @PutMapping("/{id}")
    @RequiresPermissions("device:cabinet:edit")
    @Log(title = "柜机", businessType = BusinessType.UPDATE)
    public AjaxResult edit(@PathVariable Long id,
                           @Valid @RequestBody CabinetSaveRequest request) {
        return toAjax(cabinetService.updateFromAdmin(id, request));
    }

    @DeleteMapping("/{ids}")
    @RequiresPermissions("device:cabinet:remove")
    @Log(title = "柜机", businessType = BusinessType.DELETE)
    public AjaxResult remove(@PathVariable Long[] ids) {
        return toAjax(cabinetService.removeFromAdmin(Arrays.asList(ids)));
    }
}
```

`main` 的前端目前对编辑调用 `PUT /device/cabinet` 并把 `id` 放在 body。上面的推荐契约改为 `PUT /device/cabinet/{id}`，更不容易出现 body 的 id 与 URL id 不一致。二选一并保持前端 wrapper、Controller 测试、接口文档一致；不要同时支持两种更新路径。若本阶段暂时不改 Vue，则保留 `@PutMapping`，并在 Service 中验证 `entity.getId() != null`，同时只复制允许修改的字段。

### 5.3 Service 才是业务规则的所在地

Controller 不应手动维护插槽、可借数量、Mongo 地理位置或设备状态。以柜机为例，Service 必须在事务中完成：

1. 校验 `cabinetNo` 唯一，并验证柜机类型、站点存在且可用。
2. 新增柜机时初始化其插槽；失败时整笔回滚。
3. 更新时禁止直接修改由 MQTT 事件维护的 `availableNum`、`freeSlots` 等派生值。
4. 删除前检查插槽、正在使用的充电宝和站点关联；有引用时抛业务异常，而不是依赖数据库异常文本。
5. 站点经纬度变化时，在 MySQL 成功后同步 Mongo。若无法做到分布式事务，记录待同步事件并允许补偿，不要静默吞掉 Mongo 失败。

`@Transactional` 应加在 Service 写方法，不加在 Controller。事务边界必须覆盖该服务自己的 MySQL 修改；Feign、MQTT 和 Mongo 不能被它自动回滚，分别需要幂等和补偿设计，详见第 05、06 章。

### 5.4 设备接口契约清单

前端 wrapper 需要的接口如下。`main` 提供的路径可用于核对，但请按本章权限矩阵补注解：

| 前端方法 | HTTP | 浏览器路径 | 服务内映射 | 注意事项 |
|---|---|---|---|---|
| `listCabinet` | GET | `/device/cabinet/list` | `/cabinet/list` | PageHelper 分页 |
| `getCabinet` | GET | `/device/cabinet/{id}` | `/{id}` | `query` 权限 |
| `addCabinet` | POST | `/device/cabinet` | `/` | 校验编号唯一、初始化插槽 |
| `updateCabinet` | PUT | `/device/cabinet` 或 `/{id}` | 二选一 | 禁止改派生库存 |
| `delCabinet` | DELETE | `/device/cabinet/{ids}` | `/{ids}` | 先做关联检查 |
| `getAllInfo` | GET | `/device/cabinet/getAllInfo/{id}` | `/getAllInfo/{id}` | 不要把设备密钥返回浏览器 |
| `searchNoUseList` | GET | `/device/cabinet/searchNoUseList/{keyword}` | 同名 | 仅返回选择器需要字段 |
| `getCabinetTypeList` | GET | `/device/cabinetType/getCabinetTypeList` | 同名 | 至少加登录/查询权限 |
| `listPowerBank` 等 CRUD | GET/POST/PUT/DELETE | `/device/powerBank/**` | `/powerBank/**` | 不允许管理员伪造借还状态 |
| `listStation` 等 CRUD | GET/POST/PUT/DELETE | `/device/station/**` | `/station/**` | `setData` 必须校验关联柜机 |
| `treeSelect` | GET | `/device/region/treeSelect/{parentCode}` | `/region/treeSelect/{parentCode}` | 参数限长，返回树 DTO |
| 地址/坐标转换 | GET | `/device/map/calculate*` | `/map/calculate*` | 地图 key 不返回给前端 |

`StationController.updateData()` 这类“初始化所有数据”的 GET 接口不能保留为生产管理接口。GET 不应产生写操作，且全量初始化风险极高；若确有运维需要，改成受 `device:station:repair` 权限保护的 POST，要求确认参数、操作日志和幂等执行记录。

## 6. 第四步：费用规则和会员管理接口

### 6.1 费用规则（`share-rule`）

`main` 的 `FeeRuleController.edit()` 打印 JSON 后 `return toAjax(1)`，因此前端会 toast 成功，数据库却没有任何变化。这是必须修复的真实缺陷。

```java
@PutMapping
@RequiresPermissions("rule:feeRule:edit")
@Log(title = "费用规则", businessType = BusinessType.UPDATE)
public AjaxResult edit(@Valid @RequestBody FeeRuleUpdateRequest request) {
    return toAjax(feeRuleService.updateFromAdmin(request));
}
```

`updateFromAdmin` 至少应做到：记录必须存在；免费分钟、封顶、递增阶梯不能为负；相邻阶梯无重叠且无空洞；规则正在被订单引用时，订单使用创建时的规则快照/描述，不能被改写历史金额。保存规则后要重建或刷新 Drools 会话所需的缓存，不能只更新表。

`getALLFeeRuleList()` 只用于下拉选择；仅返回 `id`、名称、是否启用和必要描述，不能把内部 Drools DSL 或管理字段暴露给普通用户接口。新增、修改、删除、列表、详情都按第 3 节权限码加方法注解；原 `main` 未加注解不能照搬。

### 6.2 会员（`share-user`）

`main` 的 `UserInfoController` 已有 `user:userInfo:*` 权限，是最接近可用的参考。后台查看详情和列表仍必须做两件事：

1. 列表 SQL 必须真实存在并支持筛选、分页，不能因 Mapper XML 漏了 `selectUserInfoList` 而返回空或启动失败。
2. 管理端编辑 DTO 不包含 `wxOpenId`、押金状态、最后登录 IP、创建时间等身份/风控字段；如需封禁用户，单独设计 `changeStatus` 命令并记录原因。

不要删除真实会员来处理投诉或测试数据。推荐软删除/禁用并保留订单审计链。导出接口需加 `user:userInfo:export`，对手机号、openid、IP 做最小化导出和脱敏；普通列表也不要返回支付或认证敏感字段。

## 7. 第五步：补齐订单后台管理接口（`share-order`）

### 7.1 为什么要新建，而不是暴露现有 Feign API

`main` 只有 `OrderInfoApiController`，它服务于内部 Feign 和业务事件；`share-ui/src/api/order/orderInfo.js` 却请求以下管理接口：

```text
GET /order/orderInfo/list
GET /order/orderInfo/{id}
GET /order/orderInfo/userList/{userId}
GET /order/orderInfo/getOrderStatisticsData
GET /order/orderInfo/getRegionOrderStatisticsData
```

不能把内部 API 直接暴露给浏览器：它的身份校验、返回字段和调用目的都不同。新建 `com.share.order.controller.OrderInfoController`，只提供只读查询、导出和固定统计；绝不提供通用 `PUT /orderInfo` 或 `DELETE /orderInfo/{id}`，否则管理员可以绕过借还、支付和消息幂等状态机。

### 7.2 先定义不会泄露内部对象的查询 DTO

接口请求和返回对象放在 `share-order` 自己的 `domain/admin`（或 `dto/admin`）目录，避免把 MyBatis 实体和 Feign DTO 直接暴露：

```java
public record OrderAdminQuery(
        Long userId,
        @Size(max = 64) String orderNo,
        @Pattern(regexp = "[012]", message = "订单状态只能是 0、1、2") String status,
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date beginTime,
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime) { }

public record OrderStatisticsQuery(
        @NotNull @DateTimeFormat(pattern = "yyyy-MM-dd") Date beginDate,
        @NotNull @DateTimeFormat(pattern = "yyyy-MM-dd") Date endDate,
        @Pattern(regexp = "day|month", message = "groupBy 只能是 day 或 month")
        String groupBy) { }
```

查询范围必须限制：例如开始和结束日期都必填、开始不晚于结束、最大跨度 366 天。不要复用 `main` 中 `OrderInfoMapper.getOrderCount(String sql)` 的 `${sql}`。它把调用者的字符串原样拼进 SQL，是直接的 SQL 注入入口，浏览器和 AI 生成的文本都绝不能进入这里。

### 7.3 Mapper 使用固定 SQL，而不是动态 SQL 字符串

新增专用 Mapper 方法和 XML。字段名、排序字段、聚合粒度全部由代码固定；用户输入只能作为 `#{}` 参数绑定。

```java
public interface OrderAdminMapper {
    List<OrderAdminRow> selectAdminList(OrderAdminQuery query);
    OrderAdminDetail selectAdminDetail(@Param("id") Long id);
    List<OrderTrendPoint> selectTrend(OrderStatisticsQuery query);
    List<RegionOrderPoint> selectRegionTrend(OrderStatisticsQuery query);
}
```

```xml
<select id="selectAdminList" resultType="com.share.order.domain.admin.OrderAdminRow">
  SELECT id, order_no, user_id, power_bank_no, start_time, end_time,
         start_station_name, end_station_name, duration,
         total_amount, deduct_amount, real_amount, pay_time, status
  FROM order_info
  <where>
    <if test="userId != null">AND user_id = #{userId}</if>
    <if test="orderNo != null and orderNo != ''">AND order_no = #{orderNo}</if>
    <if test="status != null">AND status = #{status}</if>
    <if test="beginTime != null">AND create_time &gt;= #{beginTime}</if>
    <if test="endTime != null">AND create_time &lt;= #{endTime}</if>
  </where>
  ORDER BY id DESC
</select>
```

如果日期格式（按天/按月）因 MySQL 方言不同而变化，分别写两个 mapper 方法 `selectDailyTrend` 和 `selectMonthlyTrend`，在 Java 的白名单 `switch` 中选择。不要在 `${groupBy}` 中插入 `DATE_FORMAT` 片段。

### 7.4 Controller 的完整最小实现

以下代码是推荐骨架。类名不应与内部 `api/OrderInfoApiController` 冲突；包放在 `controller`，路径使用 `/orderInfo`。

```java
@Tag(name = "后台订单管理")
@RestController
@RequestMapping("/orderInfo")
public class OrderInfoController extends BaseController {
    private final OrderAdminService orderAdminService;

    public OrderInfoController(OrderAdminService orderAdminService) {
        this.orderAdminService = orderAdminService;
    }

    @GetMapping("/list")
    @RequiresPermissions("order:orderInfo:list")
    public TableDataInfo list(@Valid OrderAdminQuery query) {
        startPage();
        return getDataTable(orderAdminService.list(query));
    }

    @GetMapping("/{id}")
    @RequiresPermissions("order:orderInfo:query")
    public AjaxResult detail(@PathVariable Long id) {
        return success(orderAdminService.getDetail(id));
    }

    @GetMapping("/userList/{userId}")
    @RequiresPermissions("order:orderInfo:list")
    public AjaxResult userList(@PathVariable Long userId) {
        return success(orderAdminService.listByUserId(userId));
    }

    @GetMapping("/getOrderStatisticsData")
    @RequiresPermissions("order:statistics:query")
    public AjaxResult statistics(@Valid OrderStatisticsQuery query) {
        return success(orderAdminService.statistics(query));
    }

    @GetMapping("/getRegionOrderStatisticsData")
    @RequiresPermissions("order:statistics:query")
    public AjaxResult regionStatistics(@Valid OrderStatisticsQuery query) {
        return success(orderAdminService.regionStatistics(query));
    }
}
```

Spring MVC 会把 GET query 参数绑定到 `OrderAdminQuery` 的 record 组件；若当前 Spring 版本对 record 绑定有问题，使用普通 JavaBean DTO（无参构造器 + getter/setter），不要为了绕过问题把验证删掉。`@Validated` 可加在 Controller 类上，以确保方法参数校验生效。

详情应在单次受控查询中返回订单、账单和**脱敏后的**会员摘要。`main` 的 `selectOrderInfoById` 通过 Feign 拿 `UserInfo` 后直接复制；推荐改为 `UserSummary`，不返回 `wxOpenId`、登录 IP 和其他不需要的 PII。账单只读，金额始终用 `BigDecimal`，不要变成 `double`。

### 7.5 订单状态机是硬边界

订单状态的含义是：`0` 充电中，`1` 已归还待支付，`2` 已完成/已支付。管理端可以看状态，不能随意写状态。

```text
设备借出事件:  空 -> 0
设备归还结算:  0 -> 1（应收大于 0）或 0 -> 2（免费）
支付成功事件:  1 -> 2
```

任何 `2 -> 0/1`、`1 -> 0`、`0 -> 2`（非免费结算）都应拒绝。支付补偿、退款、人工例外是另外的有审计命令，不能藏进 CRUD 的 `PUT`。对 MQ 重复投递，使用带旧状态条件的更新，例如 `UPDATE order_info SET status = 2 ... WHERE order_no = ? AND status = 1`，受影响行数为 0 时检查是否已经完成，再决定幂等成功或异常。

## 8. 第六步：动态菜单与权限 SQL

`share-ui` 登录后调用 `/system/menu/getRouters`，由 `sys_menu.component` 动态加载页面。业务菜单不在 Vue 路由表硬编码，也不能只新增 Controller 不配置菜单。

至少创建以下目录菜单和按钮菜单，component 路径必须和文件匹配：

```text
device/cabinetType/index      device:cabinetType:*
device/cabinet/index          device:cabinet:*
device/powerBank/index        device:powerBank:*
device/station/index          device:station:*
rule/feeRule/index            rule:feeRule:*
user/userInfo/index           user:userInfo:*
order/orderInfo/index         order:orderInfo:list,order:orderInfo:query
statistics/order/index        order:statistics:query
statistics/region/index       order:statistics:query
```

一条迁移脚本应该同时包含：菜单行、每个按钮权限行、管理员角色关联、只读角色关联（只给 list/query）。不要手工在生产库点菜单；把 SQL 放进版本化迁移，例如 `sql/migration/V20260721__business_admin_menu.sql`。字段名和主键生成方式先以 `sql/share-system.sql` 的 `sys_menu` 表结构为准。

测试角色至少有三种：

| 身份 | 预期 |
|---|---|
| 未登录 | 所有业务管理接口 401，不能从前端路由进入 |
| 只读运营 | 可 list/query，POST/PUT/DELETE 全部 403 |
| 设备管理员 | 仅拥有 device 资源权限，不能读会员、订单统计 |
| 平台管理员 | 按明确授权访问全部后台功能；不是因为用户名叫 admin 就绕过测试 |

## 9. 第七步：单元测试和 HTTP 契约测试

### 9.1 先写 Service 单元测试

Service 测试不启动 Spring。以订单管理为例，Mock `OrderAdminMapper`，验证输入校验、调用参数和返回映射：

```java
@ExtendWith(MockitoExtension.class)
class OrderAdminServiceTest {
    @Mock private OrderAdminMapper mapper;
    @InjectMocks private OrderAdminServiceImpl service;

    @Test
    void statistics_rejectsDateRangeLongerThanOneYear() {
        var query = new OrderStatisticsQuery(
                Date.from(Instant.parse("2024-01-01T00:00:00Z")),
                Date.from(Instant.parse("2025-01-02T00:00:00Z")), "day");

        assertThatThrownBy(() -> service.statistics(query))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("366");
        verifyNoInteractions(mapper);
    }

    @Test
    void detail_returnsBillsAndMaskedUserSummary() {
        // arrange mapper/Feign 返回固定数据
        // act
        // assert: 不包含 wxOpenId、手机号、登录 IP；金额仍是 BigDecimal
    }
}
```

设备 Service 必测：重复柜机编号不创建插槽；新增中的插槽初始化失败会回滚；删除被使用柜机失败；更新不会覆盖设备事件维护的派生库存；站点同步 Mongo 失败进入可重试补偿。费用规则 Service 必测：重叠阶梯、负数、引用中的历史规则、真实 `updateById` 调用。会员 Service 必测：编辑请求无法改 `wxOpenId`。

### 9.2 用 MockMvc 测 Controller 契约

下面示例不验证数据库，而是验证 HTTP 方法、JSON 字段、分页格式和权限边界。由于项目自定义鉴权依赖安全上下文，测试中应使用项目已有的登录/权限测试辅助类；若尚没有，先对 `AuthUtil` 做可替换封装，而不是在生产 Controller 中加“测试后门”。

```java
@WebMvcTest(OrderInfoController.class)
@Import({PreAuthorizeAspect.class})
class OrderInfoControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private OrderAdminService orderAdminService;

    @Test
    void list_returnsRuoyiTableContract() throws Exception {
        when(orderAdminService.list(any())).thenReturn(List.of(new OrderAdminRow()));

        mvc.perform(get("/orderInfo/list")
                    .param("pageNum", "1")
                    .param("pageSize", "10")
                    .with(adminWith("order:orderInfo:list")))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(200))
           .andExpect(jsonPath("$.rows").isArray())
           .andExpect(jsonPath("$.total").exists());
    }

    @Test
    void detail_withoutQueryPermission_isForbidden() throws Exception {
        mvc.perform(get("/orderInfo/1").with(adminWith("order:orderInfo:list")))
           .andExpect(status().isForbidden());
    }
}
```

`@WebMvcTest` 未必自动启用 PageHelper；这是正常的。该层测试只验证 `TableDataInfo` 契约，分页总数的准确性在 Mapper 集成测试中验证。若项目异常处理把权限异常转为 JSON 的 `code`，断言实际约定的 403/业务 code，不能只断言响应状态。

### 9.3 Mapper 集成测试只测固定 SQL

使用专用 MySQL 测试库或 Testcontainers（团队环境允许 Docker 时），准备最小 fixture：三种订单状态、两个地区、两个日期和两条账单。不要用本机开发库，也不要让测试依赖 Nacos 配置。

需要覆盖：

- `pageNum=1,pageSize=10` 返回 `rows` 和正确 `total`；第二页不重复。
- `orderNo`、状态、用户、时间范围组合过滤正确；空条件不产生全表危险排序。
- 详情不存在返回明确 404/业务错误，账单只属于对应订单。
- 统计按日、按月固定分组；空区间返回空数组或零值，而不是异常。
- 特殊字符（`'`, `%`, `_`, `--`）作为 `orderNo` 只被当作参数，绝不会改变 SQL 结构。
- 任何 API 都不存在 `sql`、`select`、`where` 等浏览器可控 SQL 参数。

### 9.4 前后端契约回归

为每个 `share-ui/src/api/**` wrapper 建 Vitest/MSW 测试，断言 URL、HTTP method、query/body。例如 `listOrderInfo({ pageNum: 1 })` 必须发出 `GET /order/orderInfo/list?pageNum=1`，而非 PUT 或拼错服务前缀。后端 MockMvc 测试覆盖相同路径；CI 的失败应指出“wrapper 存在、provider 不存在”或“权限码不一致”。

执行顺序：

```powershell
# 先快测试：不连 Nacos、MySQL、MQTT
mvn -pl share-modules/share-device -am test
mvn -pl share-modules/share-rule -am test
mvn -pl share-modules/share-user -am test
mvn -pl share-modules/share-order -am test

# 最后再验证前端 wrapper 和构建
Set-Location share-ui
npm install
npm run build:prod
```

若 Maven 无法下载依赖或 `.m2` 无写权限，指定可写的本地仓库，例如：

```powershell
mvn -Dmaven.repo.local="$env:TEMP\share-m2" -pl share-modules/share-order -am test
```

这只能解决本地缓存权限或下载问题，不等于测试已经通过；仍须看 Surefire 的测试数和失败数。

## 10. 验收清单

- [ ] 所有新增源码、资源、测试位于 `src/main/**`、`src/test/**` 标准目录。
- [ ] 每个修改服务声明了 `spring-boot-starter-test`，至少有 Service 单元测试和 Controller 契约测试。
- [ ] 每个后台 CRUD 方法都在**方法级**使用准确 `@RequiresPermissions`，写操作有 `@Log`。
- [ ] 设备 Service 维护关联/库存的事务规则；Controller 不直接改派生字段。
- [ ] `FeeRuleController.edit` 确实调用真实 Service 更新，并且规则边界测试通过。
- [ ] 会员编辑不能改微信身份字段，列表/详情做了敏感字段最小化。
- [ ] `/order/orderInfo/list`、`/{id}`、`/userList/{userId}` 和两个统计接口均有真实 provider；没有订单通用编辑/删除接口。
- [ ] 统计查询没有 `${sql}`、浏览器传入 SQL 或未白名单的动态列/排序片段。
- [ ] `sys_menu` 迁移、角色权限、前端 `v-hasPermi` 和 Controller 权限码完全一致。
- [ ] 未登录 401、无权限 403、只读角色不可写、重复请求和状态机非法跳转均有自动化测试。

> **重难点总结：** 后台接口的价值不是把数据库表“做成 CRUD”。它要把管理操作和设备事件、支付事件隔离开：管理端可以管理配置和查看事实，不能绕过设备借还、订单结算和支付状态机；权限既要让菜单隐藏，更要在服务端逐方法拒绝；统计只能走固定、参数化 SQL，绝不能把 AI 或浏览器生成的文本作为 SQL 执行。
