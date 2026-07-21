# 07. 订单生命周期与 RabbitMQ 消费

## 1. 订单状态机

```text
订单不存在
  -> 0 使用中（设备弹出成功）
       -> 2 已完成（归还且金额为 0）
       -> 1 待支付（归还且金额 > 0）
            -> 2 已完成（微信支付成功）
```

不允许 2 回到 0/1，也不允许未归还的 0 直接支付。状态转移应通过 Service 方法和条件更新完成，不能由通用后台 CRUD 任意修改。

## 2. 第一步：订单表与约束

仓库缺业务 DDL，最小推导结构：

```sql
create table order_info (
  id bigint primary key auto_increment,
  user_id bigint not null,
  order_no varchar(64) not null,
  message_no varchar(64) not null,
  power_bank_no varchar(64) not null,
  start_time datetime not null,
  start_station_id bigint not null, start_station_name varchar(128), start_cabinet_no varchar(64),
  end_time datetime null,
  end_station_id bigint null, end_station_name varchar(128), end_cabinet_no varchar(64),
  duration int null,
  fee_rule_id bigint not null, fee_rule varchar(500), fee_rule_version varchar(64),
  total_amount decimal(10,2), deduct_amount decimal(10,2), real_amount decimal(10,2),
  pay_time datetime null, transaction_id varchar(128) null,
  status char(1) not null default '0',
  create_by varchar(64), create_time datetime default current_timestamp,
  update_by varchar(64), update_time datetime, remark varchar(500),
  del_flag char(1) not null default '0',
  unique key uk_order_no(order_no),
  unique key uk_order_message_no(message_no),
  key idx_order_user_status(user_id, status, id),
  key idx_order_power_status(power_bank_no, status)
);

create table order_bill (
  id bigint primary key auto_increment,
  order_id bigint not null,
  bill_item varchar(500) not null,
  bill_amount decimal(10,2) not null,
  bill_type varchar(32) not null,
  create_by varchar(64), create_time datetime default current_timestamp,
  update_by varchar(64), update_time datetime, remark varchar(500),
  del_flag char(1) not null default '0',
  unique key uk_order_bill_type(order_id, bill_type),
  key idx_order_bill_order(order_id)
);
```

`message_no` 和 `bill_type` 是推荐增加字段：Redis 一小时 TTL 不能成为永久幂等依据；账单唯一键能阻止归还重放生成重复账单。

## 3. 第二步：创建订单

`SubmitOrderVo` 来自设备解锁事件。`saveOrder` 的 `main` 流程：

1. 复制 user、powerBank、起点站点/柜机；
2. 生成 8 位随机 orderNo；
3. Feign 查询 fee rule，把描述写入订单；
4. status=0、startTime=now；
5. 查询用户（结果未使用）；
6. 插入。

推荐：

```java
@Transactional
public Long saveOrder(SubmitOrderVo event) {
    validate(event);
    if (orderMapper.existsByMessageNo(event.getMessageNo())) {
        return orderMapper.idByMessageNo(event.getMessageNo());
    }
    ensureNoActiveOrder(event.getUserId());
    FeeRuleSnapshot rule = ruleClient.requireActive(event.getFeeRuleId());

    OrderInfo order = OrderInfo.start(
        orderNoGenerator.next(), event, rule, clock.instant());
    orderMapper.insert(order);
    return order.getId();
}
```

为什么不用 8 位随机字符串：碰撞虽低但不是零。使用雪花/UUID/日期+序列，并保留数据库唯一键和冲突重试。

MQ 消费线程没有 HTTP 用户上下文，`SecurityUtils.getUsername()` 常为空；系统事件的 `createBy` 应明确写 `device-event`。

### 测试 7-1：创建订单

- 合法事件 -> 一笔 status=0；
- 同 messageNo 10 次 -> 同一个订单 id；
- 同用户已有 0/1 -> 拒绝；
- rule 不存在/关闭 -> 不插入；
- 用户服务失败不应做无意义调用；
- orderNo 冲突 -> 重试或稳定失败；
- 规则描述和 version 是借出时快照。

## 4. 第三步：只保留一个下单消费者

`OrderReceiver` 中 `createOrder` 和 `submitOrder` 同时绑定：

```text
exchange=share.order
queue=share.submit.order
routingKey=share.submit.order
```

RabbitMQ 把它们当竞争消费者，一条消息随机落到其中一个：前者无幂等，后者有 Redis 幂等。必须删除 `createOrder`，只保留一个。

推荐消费者行为：

```java
@RabbitListener(...)
public void submitOrder(String content, Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
        SubmitOrderVo event = parser.parse(content);
        orderInfoService.saveOrder(event); // DB 唯一键保证永久幂等
        channel.basicAck(tag, false);
    } catch (NonRetryableMessageException e) {
        channel.basicNack(tag, false, false); // DLQ
    } catch (Exception e) {
        channel.basicNack(tag, false, true);
    }
}
```

重复消息也应 `basicAck`。`main` Redis duplicate 分支直接 return，manual ack 下会留下 unacked。

### 测试 7-2：监听器

- Spring context 中 `share.submit.order` 只有一个 listener；
- 首次消息调用 Service 1 次并 ack；
- 重复消息不新增但仍 ack；
- JSON 非法 nack/requeue=false；
- 临时 DB 异常 nack/requeue=true；
- 消费成功后 queue ready/unacked 都归零。

## 5. 第四步：归还结算

`endOrder`：按 `powerBankNo + status=0` 找活动订单，写归还信息，用 Joda `Minutes.minutesBetween` 算 duration，Feign 调规则，更新金额/状态，插入免费和超时账单。

这个方法在 `main` **没有 `@Transactional`**。订单更新后账单插入失败，会留下金额已结算但账单缺失的半状态。

推荐：

```java
@Transactional(rollbackFor = Exception.class)
public void endOrder(EndOrderVo event) {
    OrderInfo order = orderMapper.lockActiveByPowerBankNo(event.getPowerBankNo());
    if (order == null) return; // 归还重放可幂等 no-op
    int minutes = durationCalculator.minutes(order.getStartTime(), event.getEndTime());
    if (minutes < 0) throw new ServiceException("归还时间早于借出时间");

    FeeResult fee = feeClient.calculate(order.getFeeRuleId(), order.getFeeRuleVersion(), minutes);
    order.finish(event, minutes, fee);
    int changed = orderMapper.finishIfUsing(order); // where status='0'
    if (changed != 1) throw new ConcurrentModificationException();
    orderBillMapper.insertUniqueBills(order.getId(), fee);
}
```

注意远程 Feign 调用位于数据库事务内会延长锁。更成熟的做法是 saga/outbox；第一遍可接受，但必须设置短超时并保证回滚。

金额比较用 `compareTo(BigDecimal.ZERO) == 0`，不能 `doubleValue()==0`。

### 测试 7-3：归还边界

| 时长 | 预期状态 | 账单 |
|---:|---|---|
| 5 | 2 | 免费账单 1 条 |
| 6 | 1 | 免费+收费，金额 3 |
| 65 | 1 | 金额 3，不多收 |
| 1445 | 1 | 35 |
| 1446 | 1 | 99 |

故障用例：

- 没有活动订单 -> no-op；
- 两条活动订单 -> 数据完整性错误，告警；
- 规则服务失败 -> 订单/账单均不变；
- 第二条 bill 插入失败 -> 全部回滚；
- 同归还 message 重放 -> 账单不重复；
- endTime 早于 startTime -> 拒绝；
- 两消费者并发归还 -> 只有一个条件更新成功。

## 6. 第五步：订单查询

`main` 提供：

- `getNoFinishOrder(userId)`：取最新 status in (0,1)；
- `selectOrderListByUserId`：状态 0 时实时计算当前时长/预估金额，不落库；
- `selectOrderInfoById`：详情、账单、用户摘要；
- `getByOrderNo`：支付服务使用。

### 所有权校验

`GET /orderInfo/getOrderInfo/{id}` 只加 `@RequiresLogin`，但不校验订单属于当前用户。推荐：

```java
OrderInfo order = service.getForUser(id, SecurityUtils.getUserId());
```

内部按 orderNo 查询另设 `@InnerAuth` 接口。不要让会员枚举 id/orderNo。

### 实时金额测试 7-4

- status=0：按固定 Clock 计算时长和估算金额；
- 查询不更新数据库金额；
- status=1/2：返回落库金额，不重新计算；
- 无订单返回 404/空业务响应，不 NPE；
- 用户 A 访问 B 订单返回 403/404；
- 分页只返回当前用户。

## 7. 第六步：支付成功

`processPaySucess(orderNo)` 在 status=1 时改 status=2、payTime=now。推荐消息还携带 `transactionId` 和 paidAmount：

```text
orderNo, transactionId, paidAmount, paidAt, paymentEventId
```

订单端校验金额一致，然后条件更新：

```sql
update order_info
set status='2', pay_time=?, transaction_id=?
where order_no=? and status='1';
```

### 测试 7-5：支付事件

- 1 -> 2，写 payTime/transactionId；
- 重复事件 -> no-op 并 ack；
- 已是 2 -> 保持；
- status=0 -> 拒绝非法转移；
- orderNo 不存在 -> 不无限重试，进入人工核对；
- paidAmount != realAmount -> 告警且不完成订单。

## 8. 后台订单接口缺口

管理端 `share-ui/src/api/order/orderInfo.js` 请求：

```text
/order/orderInfo/list
/order/orderInfo/{id}
/order/orderInfo/userList/{userId}
/order/orderInfo/getOrderStatisticsData
/order/orderInfo/getRegionOrderStatisticsData
```

`main` 后端 `OrderInfoApiController` 没有这些接口；`orderBill.js`、`orderLog.js` 也没有 Controller。复写时先定义真实需求：

- 管理员订单分页和详情；
- 按用户过滤；
- 固定 SQL 的日期/地区统计；
- 账单只读，不提供随意增删改；
- 操作日志由状态机自动写，不提供伪造 CRUD。

不要为了适配生成的 API 文件开放危险的通用修改接口。

## 9. 禁止执行原始 SQL

`OrderInfoMapper.xml`：

```xml
<select id="getOrderCount" resultType="hashmap">${sql}</select>
```

这是任意 SQL 执行漏洞。删除 `OrderSqlVo` 和该 Mapper 方法。统计应使用固定参数化 SQL：

```sql
select date(order_date) as order_date, count(*) as order_count
from order_info
where create_time >= #{from} and create_time < #{to} and del_flag='0'
group by date(create_time)
order by order_date;
```

即使只读数据库账号，也不能执行 AI 原始 SQL，因为仍可泄露所有表和造成资源耗尽。

## 10. 本阶段完成标准

- [ ] orderNo/messageNo 有数据库唯一约束。
- [ ] submit queue 只有一个消费者。
- [ ] 重复消息 ack，永久幂等不只依赖 Redis TTL。
- [ ] endOrder 有事务/补偿，订单与账单一致。
- [ ] 状态更新使用 where old_status 条件。
- [ ] 详情和按订单号查询有所有权/内部鉴权。
- [ ] 支付事件写 transactionId、校验金额。
- [ ] 后台 UI 需要的接口明确实现或删除前端残留。
- [ ] `${sql}` 原始 SQL入口彻底删除。

> **高危：** 两个监听器绑定同一队列不是“重复处理更可靠”，而是随机竞争，导致同类消息走不同幂等和异常策略。

