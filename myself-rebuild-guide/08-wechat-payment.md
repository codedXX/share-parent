# 08. 微信支付 V3

## 1. 支付链路只相信服务端结果

```text
小程序发起支付
 -> payment 校验订单所有权/状态/金额
 -> payment_info=0
 -> 微信 JSAPI prepay
 -> 小程序 requestPayment
 -> 微信 HTTPS notify（主路径）
 -> 验签解密 Transaction
 -> payment_info=1 + outbox
 -> Rabbit payment event
 -> order 1->2

主动 queryOrderByOutTradeNo 是通知丢失时的补偿路径。
```

小程序 `requestPayment.success` 只表示客户端调起结果，不能作为订单已支付的证据。

## 2. 第一步：支付表

```sql
create table payment_info (
  id bigint primary key auto_increment,
  user_id bigint not null,
  order_no varchar(64) not null,
  pay_way tinyint not null default 1,
  transaction_id varchar(128) null,
  amount decimal(10,2) not null,
  content varchar(256) not null,
  payment_status tinyint not null default 0 comment '0未支付 1已支付 -1关闭',
  callback_time datetime null,
  callback_content longtext null,
  order_notify_status tinyint not null default 0,
  create_by varchar(64), create_time datetime default current_timestamp,
  update_by varchar(64), update_time datetime, remark varchar(500),
  del_flag char(1) not null default '0',
  unique key uk_payment_order_no(order_no),
  unique key uk_payment_transaction_id(transaction_id),
  key idx_payment_user_status(user_id, payment_status)
);

create table outbox_event (
  id bigint primary key auto_increment,
  aggregate_type varchar(64) not null,
  aggregate_id varchar(64) not null,
  event_type varchar(64) not null,
  event_id varchar(64) not null,
  payload json not null,
  status varchar(16) not null default 'NEW',
  retry_count int not null default 0,
  next_retry_time datetime null,
  create_time datetime not null default current_timestamp,
  sent_time datetime null,
  unique key uk_outbox_event_id(event_id),
  key idx_outbox_publish(status, next_retry_time)
);
```

为什么 payment/orderNo 唯一：用户重复点击或两个并发请求只能创建一条本地支付记录。

## 3. 第二步：微信商户配置

`WxPayV3Properties` 创建 `RSAAutoCertificateConfig`，需要：appid、merchantId、私钥路径、证书序列号、API v3 key、notifyUrl。

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

配置测试：缺任何字段启动失败；私钥文件不可读时给清晰错误；notifyUrl 必须 HTTPS 且微信可访问；机密不打印、不提交。

## 4. 第三步：创建本地支付记录

`PaymentInfoServiceImpl.savePaymentInfo` 的 main 逻辑是按 orderNo 查记录；没有则 Feign 查订单并复制 userId/totalAmount。

必须补四项校验：

1. 订单存在；
2. status=1 待支付；
3. 当前登录用户等于 order.userId；
4. 使用 `realAmount`，不是未来可能未扣减的 `totalAmount`。

```java
@Transactional
public PaymentInfo requirePayment(String orderNo, long currentUserId) {
    PaymentInfo existing = mapper.findByOrderNo(orderNo);
    if (existing != null) return existing;
    OrderInfo order = orderClient.requireForPayment(orderNo);
    if (!Objects.equals(order.getUserId(), currentUserId)) throw forbidden();
    if (!"1".equals(order.getStatus())) throw invalidState();
    if (order.getRealAmount().signum() <= 0) throw invalidAmount();
    return insertOrReloadOnDuplicate(order);
}
```

### 测试 8-1：记录

- 待支付自己的订单 -> paymentStatus=0；
- 同订单并发 20 次 -> 1 行；
- 用户 A 支付 B 的 orderNo -> 403；
- status 0/2 -> 拒绝；
- order 不存在/Feign 失败 -> 不插入；
- total=10、deduct=2、real=8 -> amount=8.00。

## 5. 第四步：生成微信预支付

`WxPayServiceImpl.createWxPayment` 组装 `PrepayRequest`：金额（分）、appid、mchid、description、notifyUrl、outTradeNo、payer.openid。

`main` 把金额硬编码为 1 分：

```java
amount.setTotal(1);
```

正确换算：

```java
int cents = paymentInfo.getAmount()
    .movePointRight(2)
    .setScale(0, RoundingMode.UNNECESSARY)
    .intValueExact();
amount.setTotal(cents);
```

不要用 `double * 100`，会有二进制精度误差。

把 SDK 包装为 `WechatPayGateway`，Service 不要内部直接 `new JsapiServiceExtension`，这样单测可 mock。

### 测试 8-2：预支付

| 元 | 预期分 |
|---:|---:|
| 0.01 | 1 |
| 3.00 | 300 |
| 35.00 | 3500 |
| 99.00 | 9900 |

还要断言：outTradeNo 唯一、openid 非空、merchant/appid 匹配、SDK 异常不把支付标成功、响应正确映射 `timeStamp/nonceStr/packageVal/signType/paySign`。

## 6. 第五步：异步通知验签

`/wxPay/notify` 读取：

```text
Wechatpay-Serial
Wechatpay-Nonce
Wechatpay-Timestamp
Wechatpay-Signature
原始 body
```

`NotificationParser` 完成验签和解密。只有 `tradeState=SUCCESS` 才更新业务。

回调必须加入网关白名单，但不能跳过 SDK 验签。响应为微信要求的：

```json
{"code":"SUCCESS","message":"成功"}
```

### 测试 8-3：通知

- 合法成功通知 -> payment=1；
- 相同通知重放 -> 仍一条结果；
- 签名错误/body 被改 -> FAIL，不更新；
- 非 SUCCESS -> 不标成功；
- outTradeNo 不存在 -> FAIL/人工核对；
- 微信金额与本地金额不同 -> 拒绝并告警；
- mchid/appid 不匹配 -> 拒绝；
- callbackContent 脱敏，避免持久化不必要隐私。

## 7. 第六步：支付成功与订单通知的一致性

`main` 先更新 `payment_info=1`，再直接发 Rabbit；如果进程在两步之间崩溃，微信重试时因为 paymentStatus 已为 1 直接 return，订单永久 status=1。

正确做法：

```java
@Transactional
public void markPaid(Transaction tx) {
    PaymentInfo payment = mapper.lockByOrderNo(tx.getOutTradeNo());
    if (payment.isPaid()) {
        ensureOutboxExists(payment, tx); // 重放仍修复遗漏事件
        return;
    }
    validateTransaction(payment, tx);
    mapper.markPaid(...);
    outboxMapper.insert(PaymentSucceededEvent.of(payment, tx));
}
```

后台 publisher 扫 `NEW/RETRY`，发送成功标 `SENT`；订单消费者按 eventId 幂等。

### 测试 8-4：故障注入

1. 更新 payment 后模拟进程崩溃：outbox 已存在。
2. Rabbit 不可用：outbox 保持 RETRY。
3. Rabbit 恢复：订单最终变 2。
4. 通知重放：不重复生成业务结果，缺 outbox 时能补建。
5. publish confirm nack/return：进入重试。
6. 达最大重试：告警和人工任务，不静默丢失。

## 8. 第七步：主动查单补偿

`queryPayStatus(orderNo)` 调微信 `queryOrderByOutTradeNo`。Controller 查到 SUCCESS 后复用 `markPaid`，这可以补偿通知丢失。

权限仍要校验订单属于当前用户；不要让任意登录用户枚举 orderNo。建议后台定时任务扫描“创建超过 2 分钟仍未支付”的记录主动查单，避免完全依赖小程序轮询。

### 测试 8-5：查单

- 微信 SUCCESS + 本地 0 -> 本地 1、outbox；
- 微信 NOTPAY -> 返回 false，不改；
- 微信 CLOSED -> 本地 -1；
- SDK 超时 -> 可重试，不返回假成功；
- 非订单所有者 -> 403；
- 回调与主动查单并发 -> 只产生一个最终事件。

## 9. 小程序支付调用

小程序收到预支付参数后调用 `wx.requestPayment`，随后查询服务端支付状态。改进点：

- `success` 后立即请求后端一次，不无限 2 秒轮询；
- 设置总超时和页面 `onUnload` 清理 timer；
- 用户取消显示可再次支付，不把订单标失败；
- 以后端 query/notify 结果为准；
- 真机使用 HTTPS 合法域名。

## 10. 本阶段完成标准

- [ ] payment_info/orderNo 和 transactionId 有唯一约束。
- [ ] 当前用户、订单状态、realAmount 全部校验。
- [ ] 金额从元精确转分，不再固定 1 分。
- [ ] 通知验签、金额/商户/appid 校验通过。
- [ ] payment 更新和 outbox 同事务。
- [ ] Rabbit 故障后订单最终可恢复。
- [ ] 主动查单与通知并发幂等。
- [ ] 回调白名单只跳过登录，不跳过微信验签。

> **高危：** “支付表已成功”不等于“业务订单已成功”。必须把跨服务通知作为可查询、可重试的持久状态，而不是一次方法调用。

