# 04. 费用规则与 Drools

## 1. 先用独立 Demo 学会 KIE

`drools_project` 是独立 Maven 项目，不在根 reactor 中：

```bash
mvn -f drools_project/pom.xml test
```

`DroolsConfig` 的装配链：

```text
KieServices.Factory.get()
 -> newKieFileSystem()
 -> 写入 classpath DRL
 -> newKieBuilder().buildAll()
 -> KieModule
 -> newKieContainer(releaseId)
 -> newKieSession()
```

Fact 是插入工作内存的 Java 对象，规则的 `when` 匹配 Fact，`then` 修改 Fact 或 global。

### Demo 测试 4-1

给 `DroolsDemosApplicationTests` 改成参数化断言，而不是只打印：

```java
@ParameterizedTest
@CsvSource({"99,0", "100,100", "499,100", "500,500", "999,500", "1000,1000"})
void score(int amount, int expected) {
    Order order = new Order();
    order.setAmout(amount);
    KieSession session = kieContainer.newKieSession();
    session.insert(order);
    assertThat(session.fireAllRules()).isEqualTo(1);
    session.dispose();
    assertThat(order.getScore()).isEqualTo(expected);
}
```

为什么断言规则数：金额区间应互斥；触发 0 条或多条都可能意味着规则边界错误。

## 2. 费用服务的业务模型

`FeeRule` 表字段：

```text
id, name, rule, description, status, create/update fields, del_flag
```

`rule` 是规则文本，但 `main` 的 `FeeRuleServiceImpl.calculateOrderFee` 实际使用固定 classpath `FeeRule.drl`，完全忽略 `feeRuleId` 和数据库中的 `rule`。复写时先选择一种明确策略：

另外，`FeeRuleRequestForm` 的源码字段是 `FeeRuleId`（大写开头），虽然 Lombok 当前生成的方法可被代码调用，但 JSON/Bean 约定不清晰；推荐改成 `feeRuleId`，并用 JSON 契约测试固定字段名。

### 方案 A：固定规则（推荐第一阶段）

- 数据库只保存规则名称、描述、启停；
- 每个环境发布一个版本化 DRL；
- 订单保存 `fee_rule_id` 和规则快照；
- 规则变更通过代码发布和测试。

### 方案 B：数据库动态规则

- 只允许管理员编辑受控 DRL；
- 编译前检查语法和规则包名；
- 编译结果缓存并版本化；
- 不能让普通用户输入直接成为 DRL；
- 新规则发布前跑完整边界测试，失败不替换线上容器。

不要在第一天同时实现 A+B，否则无法判断错误来自数据库、KIE 编译还是计费公式。

## 3. 设计互斥计费规则

业务意图是：前 5 分钟免费，之后每小时 3 元，24 小时封顶 35 元，超过 24 小时 99 元。先把边界写成表：

| 总时长 | 计费时长 | 预期金额 |
|---:|---:|---:|
| 0 | 0 | 0 |
| 5 | 0 | 0 |
| 6 | 1 | 3 |
| 64 | 59 | 3 |
| 65 | 60 | 3 |
| 66 | 61 | 6 |
| 1445 | 1440 | 35 |
| 1446 | 1441 | 99 |

`main` 的三个问题：

1. 免费规则条件是 `durations >= 0`，和收费规则重叠；
2. `(durations - 5) / 60 + 1` 在 65 分钟时多收一档；
3. 同一 salience 下多条规则触发，最终结果依赖议程顺序。

推荐使用互斥 DRL：

```drl
package com.share.rules
import com.share.rules.domain.vo.FeeRuleRequest
global com.share.rules.domain.vo.FeeRuleResponse feeRuleResponse

rule "free-0-to-5"
    salience 30
    when
        $r : FeeRuleRequest(durations >= 0 && durations <= 5)
    then
        feeRuleResponse.setFreeDescription("前5分钟免费");
        feeRuleResponse.setTotalAmount(0.0);
        feeRuleResponse.setFreePrice(0.0);
        feeRuleResponse.setExceedPrice(0.0);
end

rule "charge-6-to-1445"
    salience 20
    when
        $r : FeeRuleRequest(durations > 5 && durations <= 1445)
    then
        int chargeMinutes = $r.getDurations() - 5;
        int hours = (chargeMinutes + 59) / 60;
        double amount = Math.min(hours * 3.0, 35.0);
        feeRuleResponse.setFreeDescription("前5分钟免费");
        feeRuleResponse.setTotalAmount(amount);
        feeRuleResponse.setFreePrice(0.0);
        feeRuleResponse.setExceedPrice(amount);
        feeRuleResponse.setExceedDescription("超出免费时长5分钟");
end

rule "charge-over-1445"
    salience 10
    when
        $r : FeeRuleRequest(durations > 1445)
    then
        feeRuleResponse.setFreeDescription("前5分钟免费");
        feeRuleResponse.setTotalAmount(99.0);
        feeRuleResponse.setFreePrice(0.0);
        feeRuleResponse.setExceedPrice(99.0);
        feeRuleResponse.setExceedDescription("超过24小时");
end
```

Java 业务代码必须先校验 `duration >= 0`，不能把负数交给规则引擎。

## 4. 配置 KieContainer

`DroolsConfig` 读取 `classpath:rules/FeeRule.drl`。推荐在构建后检查错误：

```java
KieBuilder builder = services.newKieBuilder(fileSystem).buildAll();
Results results = builder.getResults();
if (results.hasMessages(Message.Level.ERROR)) {
    throw new IllegalStateException(results.toString());
}
return services.newKieContainer(builder.getKieModule().getReleaseId());
```

为什么启动时失败：错误规则不能等到用户归还时才暴露；启动失败比产生错误账单更容易发现和回滚。

`DroolsHelper.loadForRule` 是 main 新增的动态编译工具，但业务没有调用它。动态规则场景必须：

- `KieSession` 用 `try/finally` dispose；
- `BigDecimal.valueOf(response.getTotalAmount())`，不要 `new BigDecimal(double)`；
- 所有输出字段初始化，避免未命中规则时 null；
- 记录 rule version、duration、结果和 traceId。

## 5. 规则服务接口

| 方法 | 路径 | 调用者 |
|---|---|---|
| POST | `/feeRule/calculateOrderFee` | order |
| POST | `/feeRule/getFeeRuleList` | device |
| GET | `/feeRule/getFeeRule/{id}` | device/order |
| GET | `/feeRule/list` | 管理端 |
| POST | `/feeRule` | 管理端 |
| PUT | `/feeRule` | 管理端 |
| DELETE | `/feeRule/{ids}` | 管理端 |

内部三个 API 应加 `@InnerAuth` 并要求 Feign 传 `from-source=inner`；后台 CRUD 使用 `RequiresPermissions`。

`FeeRuleController.edit` 在 main 中只打印请求并返回成功，数据库不变。复写必须调用 `feeRuleService.updateById(feeRule)`，并对 status/name/rule 做校验。

### 测试 4-2：规则 API

- 无权限新增/修改/删除返回 403；
- 合法修改后再次查询字段确实变化；
- 删除是逻辑删除，列表不再出现；
- `getALLFeeRuleList` 只返回 status=1、未删除；
- 不存在的 id 返回 404/失败，而不是 `R.ok(null)`；
- 内部接口缺 header 时拒绝；
- DRL 编译错误返回稳定错误，不泄露服务器路径。

## 6. 计费 Service 测试

使用真实 KieContainer 的测试覆盖：

```java
@ParameterizedTest
@CsvSource({
  "0,0", "5,0", "6,3", "64,3", "65,3", "66,6",
  "1445,35", "1446,99"
})
void calculate(int duration, String amount) {
    FeeRuleRequestForm form = new FeeRuleRequestForm();
    form.setFeeRuleId(1L);
    form.setDuration(duration);
    FeeRuleResponseVo result = service.calculateOrderFee(form);
    assertThat(result.getTotalAmount()).isEqualByComparingTo(amount);
}
```

还要测试：

- `duration=null`、负数：参数校验失败；
- feeRuleId 不存在/关闭：失败；
- session 的 `dispose()` 在规则异常时仍执行；
- 规则输出金额 scale 统一为 2；
- 并行 100 次计算没有共享 global 串值；
- 数据库规则更新不会影响正在执行的 session；
- 每个 duration 只触发一条业务规则。

## 7. 订单使用规则时的快照

借出时把 `fee_rule_id` 和 `fee_rule` 描述写进订单；归还时应该使用订单创建时的规则版本，而不是只按当前 id 读取最新规则。否则管理员在用户借出后修改规则，会导致同一订单按不同价格结算。

推荐额外字段：

```sql
alter table order_info add column fee_rule_version varchar(64) null;
```

测试：创建订单后修改规则，归还仍使用旧版本；新订单使用新版本。

## 8. 规则阶段完成标准

- [ ] Demo 的金额边界有断言，不只打印日志。
- [ ] FeeRule.drl 条件互斥，0/5/6/65/1445/1446 分钟全部正确。
- [ ] 未命中、负时长、null 都有受控结果。
- [ ] feeRuleId 实际参与规则选择或明确只使用版本化固定 DRL。
- [ ] KieBuilder 编译错误启动即失败。
- [ ] Session 总是 dispose，金额用 BigDecimal。
- [ ] `edit` 真正落库，内部 API 与后台 API 权限分离。
- [ ] 订单保存规则版本快照。

> **高危：** 规则重叠会产生“偶尔正确”的账单，必须用边界测试和触发规则数断言，而不是只测一个普通时长。
