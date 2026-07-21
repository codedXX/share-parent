# 03. 会员服务与 H5/小程序登录

## 1. 先拆开两种账号

项目有两套身份：

| 身份 | 来源 | 存储 | 入口 |
|---|---|---|---|
| 后台管理员 | `sys_user` 用户名/密码 | share-system + Redis | `/auth/login` |
| 共享充电宝会员 | 微信 `openid` | `user_info` + Redis 登录态 | `/auth/h5/login/{code}` |

H5 这个命名容易误导：`H5LoginService` 实际接收的是小程序 `wx.login` 的一次性 code，调用的是微信小程序 `code2Session`。复写时保留接口名可以兼容现有小程序，但文档和新代码建议称为 `MiniAppLoginService`。

## 2. 第一步：会员表

仓库没有 `user_info` DDL。下面是根据 `UserInfo` 和 Mapper 推导的开发库最小表，属于**推荐/推导**，不是 `main` 文件：

```sql
create table user_info (
  id bigint primary key auto_increment,
  wx_open_id varchar(64) not null,
  nickname varchar(64) not null,
  gender varchar(16) null,
  avatar_url varchar(512) null,
  phone varchar(32) null,
  last_login_ip varchar(64) null,
  last_login_time datetime null,
  deposit_status char(1) not null default '0' comment '0未验证 1免押 2已交押金',
  status char(1) not null default '1' comment '1有效 2禁用',
  create_by varchar(64) null,
  create_time datetime not null default current_timestamp,
  update_by varchar(64) null,
  update_time datetime null,
  remark varchar(500) null,
  del_flag char(1) not null default '0',
  unique key uk_user_info_openid (wx_open_id),
  key idx_user_info_create_time (create_time),
  key idx_user_info_status (status, del_flag)
) engine=InnoDB default charset=utf8mb4;
```

### 为什么这些约束不能省略

- `wx_open_id` 唯一约束防止两个并发首次登录创建两行会员。
- `status`、`deposit_status` 必须有默认值；`main` 首次插入只设置昵称、头像和 openid，依赖数据库默认值。
- 继承 `BaseEntity` 后，逻辑删除字段 `del_flag` 必须存在，否则 MyBatis Plus 生成的逻辑删除 SQL 与表不匹配。
- `wx_open_id` 不是前端可编辑字段，后台修改用户时要禁止改绑。

### 测试 3-1：表结构

```sql
show create table user_info;
insert into user_info(wx_open_id, nickname) values ('openid-test', 'tester');
select id, status, deposit_status, del_flag from user_info where wx_open_id='openid-test';
```

预期默认值分别为 `1`、`0`、`0`。第二次插入相同 openid 必须被唯一键拒绝。

## 3. 第二步：微信配置 Bean

`WxProperties` 使用 `@ConfigurationProperties(prefix = "wx.miniapp")`，`WxMaConfig` 将 appId/secret 装配成 `WxMaService`。

```yaml
wx:
  miniapp:
    app-id: ${WX_MINIAPP_APP_ID}
    secret: ${WX_MINIAPP_SECRET}
```

### 为什么用 `@ConfigurationProperties`

它能把配置集中映射成类型化对象，并让测试用 `@TestPropertySource` 注入假值；不要在 Java 代码里硬编码 AppID/secret。

### 测试 3-2：配置绑定

用 `ApplicationContextRunner` 或 Spring Boot 测试：

1. 注入测试 appId/secret；
2. 断言 `WxProperties` 字段正确；
3. 缺 secret 时上下文启动失败或明确报告缺失配置；
4. 确认日志不打印 secret。

## 4. 第三步：实现微信 code 登录

`UserInfoServiceImpl.wxLogin` 的 `main` 逻辑：

```text
校验 code
 -> WxMaService.getUserService().getSessionInfo(code)
 -> 取 openid
 -> 按 wx_open_id 查询
 -> 没有则插入默认会员
 -> 返回 UserInfo
```

推荐写法（关键点）：

```java
@Transactional
public UserInfo wxLogin(String code) {
    if (!StringUtils.isNotBlank(code)) {
        throw new ServiceException("微信 code 不能为空");
    }

    WxMaJscode2SessionResult session =
        wxMaService.getUserService().getSessionInfo(code);
    String openId = StringUtils.trim(session.getOpenid());
    if (!StringUtils.isNotBlank(openId)) {
        throw new ServiceException("微信未返回 openid");
    }

    UserInfo user = userInfoMapper.selectOne(
        Wrappers.<UserInfo>lambdaQuery()
            .eq(UserInfo::getWxOpenId, openId));
    if (user == null) {
        user = new UserInfo();
        user.setWxOpenId(openId);
        user.setNickname("微信用户");
        user.setDepositStatus("0");
        user.setStatus("1");
        try {
            userInfoMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发首次登录：重新读取唯一键获胜者
            user = userInfoMapper.selectOne(
                Wrappers.<UserInfo>lambdaQuery()
                    .eq(UserInfo::getWxOpenId, openId));
        }
    }
    if (!"1".equals(user.getStatus())) {
        throw new ServiceException("账号被禁用");
    }
    user.setLastLoginTime(new Date());
    user.setLastLoginIp(IpUtils.getIpAddr());
    userInfoMapper.updateById(user);
    return user;
}
```

`main` 的实现没有设置状态、押金和最后登录字段，异常统一包装成 `RuntimeException`，也没有处理并发唯一键；复写时应补上。

### 测试 3-3：微信服务单元测试

Mock `WxMaService`：

| 输入 | Mock 行为 | 预期 |
|---|---|---|
| 空 code | 不调用微信 | `ServiceException`，无插入 |
| 非法 code | SDK 抛异常 | 受控业务错误，无插入 |
| 有效新 code | 返回 openid-1 | 插入 1 行，默认状态有效/未免押 |
| 有效旧 code | 返回已有 openid | 不插入第二行，返回原 id |
| 两个并发新 code | 两次返回同 openid | 数据库只有 1 行 |
| 禁用用户 | 返回 status=2 | 拒绝登录 |
| SDK 超时 | `TimeoutException` | 可重试错误，日志带 traceId |

并发用 `ExecutorService` 启动 20 个调用，最终断言 `count(*) = 1`。仅用 Java `synchronized` 不能替代数据库唯一索引，因为多实例部署时锁不共享。

## 5. 第四步：用户端 API

`UserInfoApiController` 的路径：

| 方法 | 路径 | 用途 | 权限 |
|---|---|---|---|
| GET | `/userInfo/wxLogin/{code}` | Feign 调微信登录 | 仅内部 provider，建议 `@InnerAuth` |
| GET | `/userInfo/getLoginUserInfo` | 当前会员摘要 | `@RequiresLogin` |
| GET | `/userInfo/isFreeDeposit` | 原实现直接改押金状态 | 应改 POST + 真正支付分校验 |
| GET | `/userInfo/getUserInfo/{id}` | 服务间取会员 | `@InnerAuth`，最小 DTO |
| GET | `/userInfo/getUserCount` | 统计 | `@InnerAuth` 或统计权限 |

### 当前用户只返回自己的数据

`getLoginUserInfo` 从 `SecurityContextHolder.getUserId()` 取 id，不能接收前端传入 userId。返回 `UserVo` 时注意字段名：实体是 `avatarUrl`，VO 是 `avatar`，`BeanUtils.copyProperties` 不会自动转换。

推荐显式映射：

```java
UserVo vo = new UserVo();
vo.setNickname(user.getNickname());
vo.setAvatar(user.getAvatarUrl());
vo.setWxOpenId(user.getWxOpenId());
vo.setDepositStatus(user.getDepositStatus());
```

### 内部调用与会员入口分开

不要让网关公开 `/userInfo/getUserInfo/{id}`。内部 provider：

```java
@InnerAuth(isUser = false)
@GetMapping("/getUserInfo/{id}")
public R<UserInfo> getInfo(@PathVariable Long id) { ... }
```

Feign 请求携带 `from-source: inner`。如果必须允许后台管理员查看，另建带权限注解的管理端 Controller。

### 测试 3-4：API 权限

1. 匿名请求当前用户：401。
2. A Token 请求当前用户：只返回 A。
3. A Token 伪造 `X-User-Id: B`：仍返回 A，网关应覆盖客户端头。
4. 无 `from-source` 调内部接口：403/内部鉴权异常。
5. 管理员无 `user:userInfo:query`：403。
6. 正确权限管理员分页：200，字段不泄露支付密钥等敏感数据。

## 6. 第五步：H5/小程序 Token

`H5TokenController.login`：

1. 调 `H5LoginService.login(code)` 获取 `LoginUser`；
2. `TokenService.createToken` 生成随机 token；
3. Redis 保存完整 `LoginUser`，JWT 返回 access_token 和过期秒数。

`H5LoginService` 只把会员 id、openid、status 映射到统一登录模型：

```java
LoginUser loginUser = new LoginUser();
loginUser.setUserid(user.getId());
loginUser.setUsername(user.getWxOpenId());
loginUser.setStatus(user.getStatus());
return loginUser;
```

后续请求经 gateway `AuthFilter`：解析 JWT、检查 Redis `login_tokens:<userKey>`、注入 user id/name/key。服务内 `HeaderInterceptor` 再写入 ThreadLocal，结束后清理。

### 测试 3-5：Token 生命周期

- 有效 code -> 返回 `access_token`、`expires_in`；
- JWT claims 有 user id/name/key，但不含 secret；
- Redis 存在 key 时访问当前用户成功；
- 删除 Redis key 后同 JWT 返回 401；
- 登出删除 Redis key，再访问返回 401；
- 临近过期请求刷新 TTL；
- 伪造 JWT、空 JWT、过期 JWT 均拒绝。

## 7. 第六步：后台会员 CRUD

`UserInfoController` 已声明权限：

```text
user:userInfo:list
user:userInfo:export
user:userInfo:query
user:userInfo:add
user:userInfo:edit
user:userInfo:remove
```

但是 `UserInfoMapper.xml` 只有 `selectUserCount`，没有 Java 接口声明的 `selectUserInfoList` SQL。必须先补：

```xml
<select id="selectUserInfoList"
        parameterType="com.share.user.domain.UserInfo"
        resultType="com.share.user.domain.UserInfo">
  select id, wx_open_id, nickname, gender, avatar_url, phone,
         last_login_ip, last_login_time, deposit_status, status,
         create_by, create_time, update_by, update_time, remark, del_flag
  from user_info
  <where>
    del_flag = '0'
    <if test="nickname != null and nickname != ''">and nickname like concat('%', #{nickname}, '%')</if>
    <if test="phone != null and phone != ''">and phone = #{phone}</if>
    <if test="status != null and status != ''">and status = #{status}</if>
  </where>
  order by id desc
</select>
```

### 测试 3-6：列表和导出

- 空条件只返回 `del_flag=0`；
- nickname 模糊搜索只返回匹配项；
- status=2 能查禁用用户；
- PageHelper 的 `total` 正确；
- 导出列名和数据行数正确；
- 删除后列表不可见、数据库 `del_flag=1`；
- Mapper 方法存在时不再出现 `Invalid bound statement`。

## 8. 第七步：押金状态机

`main` 的 `isFreeDeposit()` 是占位实现：任何登录会员 GET 一次就把 `depositStatus` 改为 `1`，没有支付分校验。这不能作为真实业务。

推荐状态：

```text
0 未验证 -> 1 免押通过
0 未验证 -> 2 已交押金
1/2       -> 保持
```

实现要求：

- 使用 POST/PUT，不用 GET 修改状态；
- 服务端调用微信支付分/押金服务并验证回调；
- 前端的 `depositStatus` 只用于展示，不能作为授权依据；
- 借用前服务端再次查询数据库状态；
- 回调使用业务幂等键。

### 测试 3-7：押金安全

1. 未验证用户扫码，服务端拒绝，即使前端传 `depositStatus=1`。
2. 伪造 `/isFreeDeposit` 请求不能直接变更状态。
3. 合法支付分回调只处理一次。
4. 支付分失败、超时、签名错误均保持 0。
5. 被禁用用户即使押金状态为 1 也不能借用。

## 9. 会员统计

`UserInfoMapper.selectUserCount` 固定统计 2024 年并按月分组：缺失月份不会补 0。推荐传入年份并生成 1 到 12 月完整序列。

### 测试 3-8：统计

- 跨年数据不串年；
- 只有 1 月和 12 月时返回 12 个位置，中间月份为 0；
- 逻辑删除用户不计入；
- 空表返回空数组/12 个 0，而不是 null；
- 大数据量使用索引，不在 Java 全表加载。

## 10. 本阶段完成标准

- [ ] 会员表和唯一 openid 索引已迁移。
- [ ] 微信 SDK 被 mock 覆盖，真实 secret 不进测试日志。
- [ ] 首次登录并发只产生一条记录。
- [ ] Token Redis 生命周期和网关 401 测试通过。
- [ ] 内部接口和会员接口权限分离。
- [ ] `selectUserInfoList` XML 已补齐。
- [ ] `avatarUrl -> avatar` 显式映射。
- [ ] 押金不是前端可伪造的 GET 占位逻辑。

> **重难点：** 微信 code 一次性且短时有效，不能缓存 code 当作身份；真正需要缓存的是服务端登录态。

> **高危：** `getUserInfo/{id}` 无权限时会泄露 openid、电话和登录 IP；复写时必须收口。

