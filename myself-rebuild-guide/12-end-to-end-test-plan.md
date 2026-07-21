# 12. 完整测试与验收计划

## 1. 测试金字塔

```text
少量：真实微信/真机/真实柜机验收
中量：Nacos + MySQL + Redis + Rabbit + Mongo + MQTT 集成测试
大量：纯 Java/Mapper/Controller/前端组件单元测试
```

为什么不要只跑全链路：全链路失败时同时涉及八个服务和六类基础设施，定位慢；先用单元测试锁定业务边界，再用集成测试验证协议和配置。

## 2. 构建级验证

```bash
# 根 reactor
mvn -DskipTests compile

# 逐模块
mvn -pl share-modules/share-user -am test
mvn -pl share-modules/share-rule -am test
mvn -pl share-modules/share-device -am test
mvn -pl share-modules/share-order -am test
mvn -pl share-modules/share-payment -am test

# 独立 Drools demo
mvn -f drools_project/pom.xml test

# 管理端
cd share-ui
npm run build:prod
```

当前仓库环境执行 Maven 时曾在 `~/.m2` 创建 `maven-resources-plugin-2.6.pom.part.lock` 失败，属于本地依赖缓存写权限/缺插件问题，尚未进入项目编译。排查时可设置可写的 `-Dmaven.repo.local=<path>` 并允许下载依赖；这不是代码测试通过。

## 3. 基础数据夹具

固定测试标识，避免随机数据难定位：

```text
user: U001 deposit=1 status=1
rule: R001 5分钟免费/每小时3元/24h封顶35/超24h99
station: S001
cabinet: C001 total=2 free=0 used=2 available=2
slot: 01 -> P001, status=1
slot: 02 -> P002, status=1
powerBank: P001 electricity=95 status=1
```

Mongo 为 S001 写对应 GeoJSON。Redis、Rabbit queue、outbox 在每个测试前清理或使用唯一 namespace/messageNo。

## 4. 场景 A：首次登录

1. Mock/真实微信返回 openid-A。
2. 调 `/auth/h5/login`。
3. 断言 `user_info` 只有一行，状态有效、未免押。
4. 断言 Redis 有登录态。
5. 用 token 调当前用户。
6. 同 openid 再登录，id 不变。

失败分支：非法 code、微信超时、禁用用户、并发登录、Redis 不可用。

## 5. 场景 B：借出

1. U001 已免押且无活动订单。
2. 调 `/device/device/scanCharge/C001`。
3. 断言只锁一个 slot，MQTT 收到 `/sys/scan/submit/C001`。
4. 模拟柜机上报 unlock：

```bash
mosquitto_pub -t '/sys/powerBank/unlock' \
  -m 'mNo=borrow-001|cNo=C001|pNo=P001|sNo=01|uId=1' -q 2
```

5. 断言 P001=2、slot01=0/null、柜机计数变化。
6. Rabbit submit event 被消费。
7. 断言唯一 order status=0，规则快照正确。
8. 重放 unlock 10 次，所有状态/订单数不变。

失败分支：未免押、已有 0/1 订单、无库存、并发扫码、MQTT 断连、DB 失败、Rabbit 断开、未收到 unlock 后锁槽超时释放。

## 6. 场景 C：归还免费单

1. 把订单 startTime 设置为当前时间前 5 分钟。
2. 模拟 connected：

```bash
mosquitto_pub -t '/sys/powerBank/connected' \
  -m 'mNo=return-free-001|cNo=C001|pNo=P001|sNo=01|ety=90' -q 2
```

3. 断言设备状态恢复。
4. 订单 duration=5、金额 0、status=2。
5. 只有一条免费账单。
6. 重放不重复账单。

## 7. 场景 D：归还待支付单

1. startTime 设置为 65 分钟前。
2. connected 事件归还。
3. 断言 duration=65、realAmount=3.00、status=1。
4. 免费+收费各一条 bill。
5. 故意让第二条 bill 插入失败，断言订单更新回滚。

再重复 6/64/66/1445/1446 分钟，核对规则边界。

## 8. 场景 E：支付

1. U001 创建 orderNo 对应预支付。
2. 断言微信请求 amount=300 分、openid 正确。
3. 模拟合法 SUCCESS 通知。
4. payment_info=1、transactionId/回调时间正确。
5. outbox 有 PaymentSucceeded。
6. Rabbit 消费后 order 1->2，金额一致。
7. 通知重放 10 次，结果不重复。

失败分支：A 支付 B、金额不一致、签名错误、通知丢失、Rabbit 断开、应用在 payment 更新后崩溃、主动查单与通知并发。

## 9. 状态不变量

每个场景后执行：

```text
cabinet.free_slots + cabinet.used_slots == cabinet.total_slots
cabinet.free_slots >= 0
cabinet.used_slots >= 0
cabinet.available_num >= 0
slot.status=0 -> power_bank_id is null
slot.status=1 -> power_bank_id is not null
power_bank.status=2 -> 不应仍绑定占用 slot
每个 user 最多一个 status in (0,1) 的订单
每个 orderNo 最多一个 payment_info
order.status=2 且 realAmount>0 -> payment_info=1
```

将这些不变量做成自动 SQL/assertion，而不是人工查看。

## 10. API 冒烟矩阵

```bash
export GATEWAY=http://localhost:18080
export TOKEN='<token>'

curl -f "$GATEWAY/user/userInfo/getLoginUserInfo" -H "Authorization: Bearer $TOKEN"
curl -f "$GATEWAY/device/device/nearbyStation/39.984104/116.307503" -H "Authorization: Bearer $TOKEN"
curl -f "$GATEWAY/order/orderInfo/getNoFinishOrder" -H "Authorization: Bearer $TOKEN"
```

管理 API 额外使用管理员 token 验证 200；会员 token 验证 403。微信 notify 用签名 fixture，不用普通 curl 伪造成功。

## 11. 故障注入矩阵

| 故障 | 预期 |
|---|---|
| rule 下线 | 归还不产生半订单/半账单，可重试 |
| Rabbit 下线 | outbox 保留，恢复后最终投递 |
| Redis 下线 | 登录/幂等明确失败，不默默绕过 |
| Mongo 下线 | 附近站点降级/错误，不影响订单库 |
| EMQX 下线 | 扫码不返回假成功，slot 可补偿释放 |
| 微信超时 | payment 保持未支付，可主动查单 |
| MySQL deadlock | 有界重试，不重复业务 |
| 重复/乱序消息 | 状态机拒绝非法转移，业务幂等 |

## 12. 性能与稳定性

- 100 个并发扫码同一柜机：成功数不超过可用 slot；
- 1000 条重复 MQTT/Rabbit 消息：数据库结果唯一；
- 附近站点 1000 点：Mongo 使用 2dsphere，接口 p95 可接受；
- 订单分页不 N+1 调 user/rule；
- 支付 callback 快速返回，后续通知异步；
- 轮询有上限，页面卸载不继续请求。

## 13. 最终签收

- [ ] 所有模块编译和自动测试通过。
- [ ] 管理端生产构建通过。
- [ ] 微信开发者工具和真机 HTTPS 回归通过。
- [ ] 借、还、付三条主线和失败补偿通过。
- [ ] 重复消息/回调不产生重复记录。
- [ ] 状态不变量全通过。
- [ ] 权限矩阵、IDOR、XSS、原始 SQL 安全测试通过。
- [ ] 配置模板可在新机器复现，secret 不入库。

