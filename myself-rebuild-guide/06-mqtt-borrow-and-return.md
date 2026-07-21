# 06. MQTT 设备协议与借还事件

## 1. 先把协议当成公共契约

`main` 用 Paho MQTT，设备 payload 不是 JSON，而是 `key=value|key=value`：

```text
mNo=msg-001|cNo=C001|pNo=P001|sNo=01|uId=7|ety=92
```

`ProtocolConvertUtil` 负责双向转换：

- `convertJson`：去换行、按 `|` 分组、按 `=` 拆键值；
- `convertString`：遍历 JSONObject 键，重新拼接字符串。

**为什么先写协议单测：** 设备故障经常不是业务算法，而是一个分隔符、缺字段或编码差异。协议解析错误必须在进入事务前被拒绝。

## 2. Topic 与字段

| 方向 | Topic | 字段 | 业务含义 |
|---|---|---|---|
| 服务订阅 | `/sys/powerBank/connected` | `mNo,cNo,pNo,sNo,ety` | 充电宝插回柜机 |
| 服务发布 | `/sys/scan/submit/{cabinetNo}` | `mNo,cNo,pNo,sNo,uId` | 请求柜机解锁 |
| 服务订阅 | `/sys/powerBank/unlock` | `mNo,cNo,pNo,sNo,uId` | 柜机确认弹出 |
| 服务订阅 | `/sys/property/post` | 未实现 | 属性上报占位 |

字段缩写来自 `PowerBankUnlockHandler`/`PowerBankConnectedHandler`：

```text
mNo = messageNo
cNo = cabinetNo
pNo = powerBankNo
sNo = slotNo
uId = userId
ety = electricity
```

建议新协议直接使用完整 JSON 字段，或在网关层版本化：`protocolVersion=2`。当前解析器不能处理值中包含 `|` 或 `=`，也没有类型校验。

## 3. 协议解析实现和测试

推荐解析器：

```java
public DeviceMessage parse(String raw) {
    if (raw == null || raw.isBlank()) throw invalid("empty payload");
    Map<String, String> values = new LinkedHashMap<>();
    for (String pair : raw.replace("\\r", "").replace("\\n", "").split("\\|")) {
        String[] parts = pair.split("=", 2);
        if (parts.length != 2 || parts[0].isBlank()) {
            throw invalid("invalid pair");
        }
        values.put(parts[0].trim(), parts[1].trim());
    }
    return DeviceMessage.required(values);
}
```

注意 `split("=", 2)`，这样值中有等号时不会被静默丢弃。

### 测试 6-1：协议边界

| 输入 | 预期 |
|---|---|
| 完整合法 payload | 正确得到所有字段 |
| 空字符串/null | 拒绝 |
| 缺 mNo/cNo/pNo/sNo | 拒绝 |
| `ety=abc` | 拒绝，不进入数据库 |
| `ety=-1` 或 `101` | 拒绝 |
| 多余字段 | 记录告警，按兼容策略忽略 |
| 值包含 `=` | 仍正确解析 |
| 重复 key | 拒绝或明确最后值策略，不能静默不确定 |
| UTF-8/换行 | 按协议处理 |
| `convertString(convertJson(x))` | 关键字段语义等价 |

## 4. MQTT 客户端生命周期

`EmqxClientWrapper` 在 `@PostConstruct` 创建客户端并设置 callback；`connect()` 会：

1. 使用用户名/密码建立连接；
2. 开启 automatic reconnect；
3. 订阅三个服务端 Topic；
4. `publish` 使用 QoS 2。

但是 `init()` 中 `connect()` 被注释。`main` 还把连接异常打印后吞掉，发布失败时接口照样继续。

### 推荐实现

```java
@PostConstruct
void init() {
    if (!properties.isEnabled()) return;
    client = new MqttClient(...);
    client.setCallback(callback);
    connectOrFail();
}

void publish(String topic, String payload) {
    ensureConnected();
    client.publish(topic, new MqttMessage(payload.getBytes(StandardCharsets.UTF_8)));
}
```

更稳妥的做法是让设备服务启动但标记 `DEGRADED`，扫码 API 检查 MQTT readiness；不要让 publish 返回 void 后无法告诉调用方发送失败。

### QoS 和会话

- QoS 2 能降低重复传递概率，但不能替代业务幂等；
- `cleanSession=true` 重连后需重新订阅，必须确认自动重连 callback 会恢复订阅；
- `MemoryPersistence` 重启丢失未完成交付，生产可用文件持久化或由业务事件补偿；
- 每个实例 clientId 必须唯一，否则 Broker 会踢掉旧连接。

### 测试 6-2：客户端

使用 Testcontainers EMQX 或 Mosquitto：

1. 启动服务，断言连接和订阅成功；
2. 发布一条 connected，断言 handler 被调用；
3. Broker 暂停，publish 返回明确失败；
4. Broker 恢复，断言重新连接并订阅；
5. 两个服务实例使用不同 clientId；
6. 关闭服务，确认客户端 disconnect，不留线程。

## 5. Handler 工厂

`MessageHandlerFactoryImpl` 启动时扫描 `MassageHandler` Bean，读取 `@GuiguEmqx(topic=...)` 建立 map：

```text
topic -> PowerBankUnlockHandler
topic -> PowerBankConnectedHandler
topic -> PropertyPostHandler
```

`OnMessageCallback.messageArrived` 根据 topic 取 handler、解析 payload、调用 `handleMessage`。

风险：工厂对每个 Bean 直接 `.iterator().next()`，某个 handler 没标注就会启动异常。应在没有注解时跳过并记录 error，且拒绝重复 topic。

### 测试 6-3：路由

- 已知 topic 找到唯一 handler；
- 未知 topic 不抛异常，只记录并计数；
- handler 缺注解时启动报清晰错误；
- 两个 handler 注册相同 topic 时启动失败；
- callback 中 handler 抛异常不影响 MQTT 网络线程，消息进入重试/DLQ策略。

## 6. 借出事件 `PowerBankUnlockHandler`

原始逻辑：

1. 睡眠 5 秒等待柜机状态；
2. Redis `powerBank:unlock:<mNo>` 去重；
3. 读取柜机、充电宝、slot、userId；
4. 查站点；
5. 充电宝状态改为 2（已租用）；
6. slot 改为 0 且 `powerBankId=null`；
7. 柜机 freeSlots+1、usedSlots-1、availableNum-1；
8. 发布 `share.submit.order` 创建订单。

### 推荐事务顺序

不要先写 Redis 去重再校验参数；否则一次坏消息会锁住 messageNo 一小时。推荐：

```text
解析/校验
 -> 事务内条件更新 power_bank、slot、cabinet
 -> 写 outbox submit-order event
 -> 提交
 -> 发布 Rabbit
 -> 标记 outbox 已发送
```

设备状态更新必须有条件：

```sql
update cabinet_slot
set status='0', power_bank_id=null
where cabinet_id=? and slot_no=? and status='2' and power_bank_id=?;
```

影响行数不是 1 时，说明重复、乱序或状态不匹配，不应继续创建订单。

### 测试 6-4：借出

- 合法解锁事件：设备三表状态正确，发送一条订单事件；
- 重复 mNo：设备和订单不重复，重复 MQ 被 ack；
- 缺字段/未知柜机/slot 已非锁定：拒绝，不发订单；
- 数据库中途失败：事务回滚，Redis/outbox 可重试；
- Rabbit 不可用：outbox 保留，之后补发；
- 乱序 connected 先于 unlock：不把未知事件当成功借出。

## 7. 归还事件 `PowerBankConnectedHandler`

原始逻辑：

1. Redis `powerBank:connected:<mNo>` 去重；
2. 读取柜机、充电宝、slot、电量；
3. 电量大于 80 -> powerBank status=1，否则 status=3；
4. slot 设为占用、绑定 powerBank；
5. cabinet freeSlots-1、usedSlots+1；可用充电宝时 availableNum+1；
6. 找到站点，发送 `share.end.order`。

源码注释说“无订单时初始化插入，有订单时归还”，但实现没有区分，也没有处理不存在的 powerBank/cabinet/slot，容易 NPE。应先查当前活动订单或设备注册状态，再决定初始化/归还。

### 测试 6-5：归还

- 电量 80：按业务定义确认是充电中还是可用，边界必须固定；
- 电量 81：可用数量 +1；
- 重复事件：不重复增加数量、不重复结束订单；
- slot 已占用别的充电宝：拒绝并告警；
- 不存在活动订单：设备状态可更新，但订单事件应有明确 no-op/异常策略；
- 站点缺失：事务回滚或进入待补偿队列；
- end order MQ 失败：outbox 可补发。

## 8. 属性上报

`PropertyPostHandler` 在 main 是空实现。第一遍复写可以只做协议接收和审计：

```text
解析 mNo/cNo/属性集合
 -> 校验设备身份和时间戳
 -> 保存 device_property_event
 -> 更新最新遥测快照
```

不要把空 handler 注册成“业务成功”，否则设备会误以为属性已经落库。

## 9. 延迟解锁补偿

`MqConst.CANCEL_UNLOCK_SLOT_DELAY_TIME=5`（秒）和 `share.unlock.slot` 暗示原计划：扫码锁槽后，若柜机没有确认弹出，延迟释放 slot。复写时必须补齐：

1. 锁槽时写 `unlock_pending` 事件；
2. 发送延迟消息；
3. 解锁成功时标记事件完成；
4. 延迟消费者只释放仍为锁定且 messageNo 未完成的 slot；
5. 释放操作条件更新，避免覆盖已成功借出的 slot。

### 测试 6-6：延迟补偿

- 5 秒内收到 unlock：延迟消息执行 no-op；
- 没收到 unlock：slot 回到可用状态；
- unlock 与延迟任务并发：最终只能有一种状态；
- 延迟消息重复：幂等；
- Broker 插件不可用：启动检查失败或禁用该功能，不静默丢消息。

## 10. 端到端 MQTT 模拟

准备数据库：C001、slot 01、P001 status=1/electricity=95、站点 S001、规则 R001、用户 U001。

服务端订阅：

```bash
mosquitto_sub -h 127.0.0.1 -t '/sys/#' -v
```

触发借出成功确认：

```bash
mosquitto_pub -h 127.0.0.1 -t '/sys/powerBank/unlock' -q 2 \
  -m 'mNo=m-borrow-1|cNo=C001|pNo=P001|sNo=01|uId=1'
```

预期：slot 0、powerBank 2、cabinet 可用数量减少，Rabbit 创建订单。

触发归还：

```bash
mosquitto_pub -h 127.0.0.1 -t '/sys/powerBank/connected' -q 2 \
  -m 'mNo=m-return-1|cNo=C001|pNo=P001|sNo=01|ety=92'
```

预期：slot 1、powerBank 1、cabinet 可用数量增加，订单进入待支付/免费完成。

## 11. 本阶段完成标准

- [ ] 协议解析器对缺失/越界/分隔符有单测。
- [ ] MQTT 连接状态可观测，publish 失败不会假成功。
- [ ] Topic 到 handler 唯一且未知消息不拖垮网络线程。
- [ ] 借出、归还三表状态更新有条件更新和事务/补偿。
- [ ] Redis 幂等不在坏消息前锁死重试，重复消息必 ack。
- [ ] 延迟释放锁槽的补偿链路可测试。
- [ ] PropertyPost 不再是空成功处理。

> **高危：** QoS 2 只保证 MQTT 传输语义，不能保证 MySQL 更新、Rabbit 发送和订单创建原子一致；这三层仍需幂等和 outbox。

