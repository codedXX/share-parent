# 11. 统计模块与安全收口

## 1. 先区分两套统计

| 目录/服务 | 目标 | 当前状态 |
|---|---|---|
| `share-modules/share-stastics` + `views/stastics/*` | 会员月度、自然语言订单统计 | user 可运行；AI 服务缺失且原始 SQL 高危 |
| `views/statistics/order|region` | 固定订单/地区报表 | 前端存在，后端接口和 Mapper XML 缺失 |

`stastics` 是拼写错误；第一轮兼容服务名，后续统一迁移为 `statistics`。

## 2. 会员统计

当前 SQL 固定 `YEAR(create_time)=2024`，返回有数据的月份。推荐接口：

```text
GET /statistics/users/monthly?year=2026
```

固定参数化 SQL，Service 补齐 12 个月 0：

```sql
select month(create_time) as month_no, count(*) as user_count
from user_info
where create_time >= #{start}
  and create_time < #{end}
  and del_flag='0'
group by month(create_time)
order by month_no;
```

### 测试 11-1

- 2025/2026 数据严格分年；
- 缺失月份补 0；
- 逻辑删除不计；
- 非法 year 拒绝；
- 空表仍返回 12 个点；
- 统计权限和数据范围生效。

## 3. 删除 AI 原始 SQL 执行链

`main` 的危险链路：

```text
用户 message
 -> localhost:8899/ai/generateSql
 -> AI 返回任意字符串
 -> Feign OrderSqlVo.sql
 -> MyBatis ${sql}
 -> 数据库直接执行
```

这允许读取敏感表、UPDATE/DELETE、UNION、information_schema 探测和耗时查询。必须删除 `${sql}`。

### 推荐方案 A：固定指标

前端选择：指标、维度、日期范围。后端为每个指标写固定 Mapper：订单数、金额、地区、站点。

### 推荐方案 B：受限查询 DSL

AI 只返回结构化 JSON：

```json
{
  "metric": "ORDER_COUNT",
  "dimension": "DAY",
  "from": "2026-07-01",
  "to": "2026-08-01",
  "filters": {"provinceCode": "110000"}
}
```

后端 enum 白名单校验，再调用预写 SQL。AI 永远不能输出最终 SQL。

### 测试 11-2：恶意输入

以下内容必须无法进入数据库 SQL：

```text
DROP TABLE order_info
UPDATE user_info SET status=2
UNION SELECT password FROM sys_user
information_schema.tables
sleep(30)
```

还要限制日期范围、结果行数、调用频率、AI 超时；审计只保存脱敏意图和最终结构化查询。

## 4. 固定订单/地区报表

建议接口：

```text
GET /orderInfo/statistics/daily?from=&to=
GET /orderInfo/statistics/region?from=&to=&level=province
```

金额只统计完成订单或明确指定状态，按 `real_amount` 求和；时区固定 Asia/Shanghai。地区维度使用订单创建时站点快照，避免站点迁移改变历史报表。

### 测试 11-3

- 日边界 `00:00:00` 使用 `[from,to)`；
- status 0/1/2 是否计入符合产品定义；
- 退款/0 元订单单独测试；
- BigDecimal 聚合精确；
- 跨月/跨年、无数据、未知地区；
- 同一订单只统计一次。

## 5. 权限矩阵

| 接口类型 | 匿名 | 会员 | 管理员 | 内部 Feign |
|---|---|---|---|---|
| 微信登录 | 允许 | 允许 | 允许 | 不需要 |
| 支付 notify | 允许但必须验签 | 同左 | 同左 | 不需要 |
| 当前会员信息/订单 | 禁止 | 仅自己 | 按权限 | 不需要 |
| 扫码/附近站点 | 附近可按产品决定；扫码禁止 | 允许 | 允许 | 不需要 |
| 管理 CRUD | 禁止 | 禁止 | `RequiresPermissions` | 不需要 |
| 按 id 查用户/订单/规则 | 禁止 | 禁止 | 专用管理接口 | `@InnerAuth` |
| 统计 | 禁止 | 禁止 | 统计权限 | provider 内部 |

测试不能只验证“没有 Token 401”；必须验证 IDOR：会员 A 访问 B 的 userId/orderId/orderNo。

## 6. 内部调用鉴权

新增 Feign provider 应遵循已有 system 模式：

- Feign 传 `from-source: inner`；
- provider 加 `@InnerAuth`；
- 网关 `AuthFilter` 删除客户端伪造的 `from-source`；
- 内部接口不加入匿名白名单；
- 服务网络层不直接暴露公网。

注意 `PreAuthorizeAspect` 当前通过 `method.getAnnotation` 检查方法注解；只把权限写在类上可能不生效，需测试或修正为 Spring merged annotation 查找。

## 7. 其他安全问题

- 地图 InfoWindow 拼原始 HTML：存储型 XSS；
- 小程序 hardcoded map key/AppID：按平台限制并轮换；
- URL 中微信 code：改 POST body并避免访问日志记录；
- callbackContent：可能含敏感支付数据，最小化保存、加密和保留期；
- Controller 管理端缺权限：前端隐藏按钮无效；
- scan QR 只含可猜 cabinetNo：加签名/版本；
- 日志中不得输出 token、secret、完整支付签名和用户 openid。

## 8. 本阶段完成标准

- [ ] `${sql}` 和 `OrderSqlVo` 已删除。
- [ ] 固定/DSL 统计全部参数化。
- [ ] 年月/地区/金额边界测试通过。
- [ ] 匿名、会员、管理员、内部调用权限矩阵自动化。
- [ ] IDOR、XSS、恶意 AI 输入测试通过。
- [ ] 内部 provider 使用 `@InnerAuth`。
- [ ] 敏感配置与日志脱敏。

> **最高风险：** AI 生成 SQL 后做字符串 startsWith("SELECT") 检查仍不安全；必须让 AI 输出受限意图，由后端控制 SQL。

