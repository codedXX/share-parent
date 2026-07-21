# 05. 设备域：站点、柜机、插槽、充电宝与地图

## 1. 设备域的对象关系

```text
region（省/市/区）
  └─ station（运营站点）
       ├─ cabinet（柜机）
       │    ├─ cabinet_type（柜机规格）
       │    └─ cabinet_slot（插槽）
       │          └─ power_bank（充电宝）
       └─ fee_rule（规则服务中的费用规则）

station_location（MongoDB GeoJSON）
  └─ stationId -> station.id
```

为什么要同时存统计字段和明细关系：站点列表需要快速显示“可借/可还”，直接读柜机的 `available_num/free_slots` 比每次 count 插槽快；但这些字段是派生值，必须由设备事件事务维护，不能让后台随意改成负数。

## 2. 第一步：补齐设备表

仓库没有设备 DDL，下面是从实体、Mapper XML 和服务逻辑推导的最小结构。生产库应再补外键策略、审计和索引。

```sql
create table cabinet_type (
  id bigint primary key auto_increment,
  name varchar(64) not null,
  total_slots int not null,
  description varchar(500),
  status char(1) not null default '0',
  create_by varchar(64), create_time datetime default current_timestamp,
  update_by varchar(64), update_time datetime, remark varchar(500),
  del_flag char(1) not null default '0'
);

create table cabinet (
  id bigint primary key auto_increment,
  cabinet_no varchar(64) not null,
  name varchar(128) not null,
  cabinet_type_id bigint not null,
  total_slots int not null,
  free_slots int not null default 0,
  used_slots int not null default 0,
  available_num int not null default 0,
  description varchar(500), location_id bigint,
  status char(1) not null default '0',
  create_by varchar(64), create_time datetime default current_timestamp,
  update_by varchar(64), update_time datetime, remark varchar(500),
  del_flag char(1) not null default '0',
  unique key uk_cabinet_no(cabinet_no),
  key idx_cabinet_type(cabinet_type_id)
);

create table power_bank (
  id bigint primary key auto_increment,
  power_bank_no varchar(64) not null,
  electricity decimal(5,2) not null default 0,
  description varchar(500),
  status char(1) not null default '0',
  create_by varchar(64), create_time datetime default current_timestamp,
  update_by varchar(64), update_time datetime, remark varchar(500),
  del_flag char(1) not null default '0',
  unique key uk_power_bank_no(power_bank_no)
);

create table cabinet_slot (
  id bigint primary key auto_increment,
  cabinet_id bigint not null,
  slot_no varchar(32) not null,
  power_bank_id bigint null,
  status char(1) not null default '0' comment '0空闲 1占用 2锁定',
  create_by varchar(64), create_time datetime default current_timestamp,
  update_by varchar(64), update_time datetime, remark varchar(500),
  del_flag char(1) not null default '0',
  unique key uk_cabinet_slot(cabinet_id, slot_no),
  key idx_slot_power_bank(power_bank_id)
);

create table station (
  id bigint primary key auto_increment,
  name varchar(128) not null, image_url varchar(512), business_hours varchar(128),
  longitude decimal(10,7) not null, latitude decimal(10,7) not null,
  province_code varchar(32), city_code varchar(32), district_code varchar(32),
  address varchar(256), full_address varchar(512),
  head_name varchar(64), head_phone varchar(32),
  cabinet_id bigint not null, fee_rule_id bigint not null,
  status char(1) not null default '1',
  create_by varchar(64), create_time datetime default current_timestamp,
  update_by varchar(64), update_time datetime, remark varchar(500),
  del_flag char(1) not null default '0',
  key idx_station_region(province_code, city_code, district_code),
  key idx_station_cabinet(cabinet_id)
);

create table region (
  id bigint primary key auto_increment,
  code varchar(32) not null, parent_code varchar(32) not null,
  name varchar(128) not null, level bigint not null,
  create_by varchar(64), create_time datetime default current_timestamp,
  update_by varchar(64), update_time datetime, remark varchar(500),
  del_flag char(1) not null default '0',
  unique key uk_region_code(code)
);
```

### 状态约定

| 对象 | 状态 | 含义 |
|---|---|---|
| cabinet | 0/1/-1 | 未投入/使用中/故障 |
| slot | 0/1/2 | 空闲/占用/锁定 |
| power bank | 0/1/2/3/4 | 未投放/可用/已租用/充电中/故障 |
| station | 0/1 | 停用/正常 |
| fee_rule | 1/2 | 有效/关闭 |

**为什么要把状态表写进文档：** 源码大量使用字符串比较（如 `"1"`、`"0"`），没有枚举约束。复写时最好创建 enum/常量，并在 Controller 层拒绝未知值。

## 3. 第二步：MyBatis Plus 实体和 Mapper

实体继承 `BaseEntity`，自动获得自增 id、审计字段和逻辑删除；类名按驼峰转下划线映射表名。复杂查询放 XML：

- `CabinetMapper.xml`：柜机与类型关联、全部信息、未使用柜机；
- `CabinetTypeMapper.xml`：规格查询；
- `PowerBankMapper.xml`：充电宝查询；
- `StationMapper.xml`：站点和柜机/费用规则关联。

`CabinetSlot.powerBankId` 使用 `@TableField(updateStrategy = FieldStrategy.IGNORED)`，目的是允许归还/弹出时把列更新成 `NULL`。没有它，MyBatis Plus 可能忽略 null，插槽会继续指向已取走的充电宝。

### Mapper 测试 5-1

用 Testcontainers MySQL 或 H2（需适配 MySQL 方言）：

1. 插入一个 cabinet type，校验 `total_slots` 非空；
2. 插入柜机和两个 slot；
3. 更新 slot `powerBankId=null`，查询确认列真的为空；
4. 重复 cabinetNo/powerBankNo/slotNo 由数据库唯一键拒绝；
5. 逻辑删除后普通查询不可见；
6. 关联查询返回 `cabinetTypeName/cabinetNo/feeRuleName`。

## 4. 第三步：CRUD 服务与 Controller

主要 Controller：

| Controller | 路径 | 作用 |
|---|---|---|
| `CabinetTypeController` | `/cabinetType` | 规格 CRUD、列表 |
| `CabinetController` | `/cabinet` | 柜机 CRUD、详情、未使用列表 |
| `PowerBankController` | `/powerBank` | 充电宝 CRUD |
| `StationController` | `/station` | 站点 CRUD、同步 Mongo 位置 |
| `RegionController` | `/region/treeSelect/{code}` | 地区树 |
| `MapController` | `/map/calculateLatLng/{keyword}` | 地址转坐标 |
| `DeviceController` | `/device/nearbyStationList/{lat}/{lng}` | 管理端附近站点 |
| `DeviceApiController` | `/device/scanCharge`、`nearbyStation`、`getStation` | 小程序入口 |

### Service 层为什么不能只调用 `baseMapper`

`CabinetServiceImpl` 需要维护插槽初始化、可用数量和关联关系；`StationServiceImpl` 需要 MySQL 成功后同步 Mongo 位置；设备事件需要事务。Controller 只做参数/权限/响应转换，不能把这些规则散落在 HTTP 层。

### 事务写法

```java
@Transactional(rollbackFor = Exception.class)
public boolean saveStation(Station station) {
    validateCabinetAndRule(station);
    boolean saved = save(station);
    if (!saved) return false;
    stationLocationRepository.save(toLocation(station));
    return true;
}
```

Mongo 和 MySQL 不是同一个本地事务。真正可靠的实现应使用 outbox/补偿；至少要记录同步失败并可重放，不能静默吞异常。

### Controller 权限

`CabinetTypeController` 部分方法有 `RequiresPermissions`，但 cabinet、powerBank、station 等 `main` Controller 多数没有权限注解。复写时为每个管理动作声明：

```text
device:cabinet:list/query/add/edit/remove
device:cabinetType:list/query/add/edit/remove
device:powerBank:list/query/add/edit/remove
device:station:list/query/add/edit/remove
```

### 测试 5-2：CRUD API

- 匿名访问管理 CRUD：401；
- 登录但无权限：403；
- 合法新增/修改后再次查询字段变更；
- 删除有引用的柜机时拒绝，或执行明确级联策略；
- 分页 total 正确；
- 非法电量、负插槽、重复编号返回 400；
- 站点保存失败时 Mongo 不应留下孤儿位置；
- 地区树对根 code、叶子 code、未知 code 有稳定返回。

## 5. `main` 中的删除/查询风险

- 柜机/站点删除使用 `removeBatchByIds`，可能绕过你期望的自定义级联清理；删除前必须检查 slot/order 引用。
- `StationMapper.xml` 的地区筛选参数/列存在写反风险，必须用 province/city/district 三组数据各测一次。
- 一些前端 API（cabinetLocation、cabinetSlot）没有后端 Controller，是生成残留；不要为了“接口齐全”先写无业务含义的空 CRUD。

## 6. 第四步：MongoDB 附近站点

推荐给 `StationLocation` 明确集合名（`main` 原类没有 `@Document`）：

```java
@Document("stationLocation")
public class StationLocation {
    @Id private String id;
    private Long stationId;
    private GeoJsonPoint location;
    private Date createTime;
}
```

坐标顺序是 `[longitude, latitude]`。`DeviceServiceImpl.nearbyStation`：

1. 将请求经纬度转 `GeoJsonPoint(longitude, latitude)`；
2. 以 50km `Circle` 做 `withinSphere`；
3. 取 stationId 列表；
4. MySQL 批量查 Station；
5. 批量查柜机和费用规则；
6. 组装 `StationVo`，计算距离和可借/可还状态。

Mongo 初始化：

```javascript
use share_device
db.stationLocation.createIndex({location: "2dsphere"})
db.stationLocation.insertOne({
  stationId: NumberLong(1),
  location: {type: "Point", coordinates: [116.307503, 39.984104]},
  createTime: new Date()
})
```

### 空集合必须先处理

`main` 第一版查询在 Mongo 空结果时可能对空 stationId 列表执行 MySQL `IN ()`；另一重载直接返回 `null`。推荐统一返回空数组：

```java
if (locations.isEmpty()) return Collections.emptyList();
```

### 测试 5-3：地理查询

写入：近点（1km）、边界点（约 50km）、远点（100km）：

- 查询中心点只返回半径内点；
- 经纬度反写时测试应失败，避免“坐标顺序错误但结果恰好非空”；
- 无位置返回 `[]`，不是 null/500；
- station 缺 cabinet 或 fee rule 时返回受控数据或跳过，并记录告警；
- 结果距离单位固定（米或公里），API 文档与前端显示一致；
- 使用 `2dsphere` 索引，慢查询日志无全表扫描。

## 7. 地图服务

`MapServiceImpl.calculateLatLng` 调腾讯地理编码 API；但 `calculateDistance` 的真实 HTTP 代码被注释，当前返回 0~100 的随机数。随机距离只能用于页面占位，不能用于排序、计费或运营。

推荐：

- 把腾讯 key 放 Nacos/Secret；
- RestTemplate 设置连接/读取超时；
- 对 `status != 0`、空 routes、限流分别处理；
- 单元测试 mock HTTP，不依赖真实 key；
- 服务器端距离使用 Haversine 或地图 API，统一单位。

### 测试 5-4：地图

- 有效地址返回 longitude/latitude；
- 不存在地址返回业务错误；
- 第三方超时在有限时间内返回“地图服务暂不可用”；
- 同一坐标距离自身为 0；
- 随机数实现被静态检查禁止；
- key 不出现在日志和 Git。

## 8. 小程序入口 `scanCharge`

`DeviceServiceImpl.scanCharge(cabinetNo)` 的原始流程：

1. Feign 查当前用户押金状态；
2. 查是否存在状态 0/1 的未完成订单；
3. 按柜机选择电量最高的可用充电宝；
4. 将 slot 状态改为 2（锁定）；
5. 生成 `mNo`，向 `/sys/scan/submit/{cabinetNo}` 发布 MQTT；
6. `Thread.sleep(2000)` 后返回 status=1。

状态含义：

| status | 含义 |
|---|---|
| 1 | 已发起解锁，前端轮询订单 |
| 2 | 有未归还订单 |
| 3 | 有未支付订单 |

### 必须修复的设备并发问题

- 仅查询后更新 slot，两个请求可同时选中同一 slot；使用数据库条件更新 `where status=1 and id=?`，影响行数必须为 1。
- slot 锁定后 MQTT 发布失败，必须释放 slot 或进入可重试状态；不能返回成功。
- Redis 幂等键应在参数校验和事务成功后建立，失败不能阻断重试。
- HTTP 请求中 `Thread.sleep` 会占用 Tomcat 线程；改为设备事件+异步状态查询。
- cabinet `available_num` 等派生计数必须用原子条件更新并校验不小于 0。

### 测试 5-5：扫码

- 押金状态 0：拒绝且不锁 slot、不发 MQTT；
- 有状态 0 订单：提示先归还；
- 有状态 1 订单：引导支付；
- 无库存：明确“无可用充电宝”；
- 两个并发扫码：只有一个成功锁定 slot；
- MQTT broker 不可用：接口返回失败并释放/补偿锁；
- 重复 cabinetNo/非法扫码内容：400；
- 设备事件到达后前端能看到唯一订单。

## 9. 设备域完成标准

- [ ] 所有业务表、唯一键、索引、逻辑删除默认值已迁移。
- [ ] CRUD 每个管理动作有权限注解和参数校验。
- [ ] slot/powerBank/cabinet 状态转移有状态机测试。
- [ ] MySQL 与 Mongo 同步失败可观察、可补偿。
- [ ] 空 Mongo 结果返回空数组，不执行 `IN ()`。
- [ ] 距离不再使用随机数。
- [ ] 扫码使用条件更新防并发，MQTT 失败不返回假成功。
- [ ] `cabinetLocation` 等无后端残留 API 已删除或明确实现。

> **重难点：** 可用数量是派生缓存，不是事实来源；任何人工修改都可能破坏设备事件计算，后台应只提供受控校准操作并记录原因。
