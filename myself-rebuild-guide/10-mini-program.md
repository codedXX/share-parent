# 10. 小程序端

## 1. 不要手写 `mp-weixin` 编译产物

仓库的 `mp-weixin` 含 `common/vendor.js`、`_export_sfc`、`createSSRApp`，WXML/WXSS/JS 都是 uni-app 编译输出；缺少原始 `.vue`、`pages.json`、`manifest.json`、`package.json`。

正确复写方式：

1. 新建 uni-app Vue3 源工程，例如 `share-miniapp`；
2. 按本文重写页面和 composable；
3. 编译输出到微信小程序目录；
4. `mp-weixin` 作为构建产物，不在其中维护业务源码。

如果直接改编译 JS，下次构建会全部覆盖，也无法做组件单测。

## 2. 页面和业务流程

| 页面 | 原路径 | 主要动作 |
|---|---|---|
| 首页 | `pages/index/index` | 地图、附近门店、扫码、个人中心 |
| 附近门店 | `pages/nearbystores/nearbystores` | 定位、站点列表/marker |
| 门店详情 | `pages/detail/detail` | 站点详情、可借可还、扫码 |
| 个人中心 | `pages/center/center` | 用户摘要、订单入口 |
| 订单列表 | `pages/order/order` | 分页加载当前用户订单 |
| 订单详情 | `pages/orderDetail/orderDetail` | 状态、费用、支付 |
| 免押验证 | `pages/verify/verify` | 原实现直接改状态，需重做 |

## 3. 第一步：请求层

`main` 的 `utils/request.js` 固定 `http://localhost:18080`。真机 localhost 指手机自身，生产小程序还要求 HTTPS 合法域名。

推荐：

```js
const BASE_URL = import.meta.env.VITE_API_BASE_URL

export function request({ url, method = 'GET', data, auth = true }) {
  return new Promise((resolve, reject) => {
    const token = uni.getStorageSync('token')
    uni.request({
      url: BASE_URL + url,
      method,
      data,
      timeout: 10000,
      header: auth && token ? { Authorization: `Bearer ${token}` } : {},
      success(res) {
        if (res.statusCode === 401 || res.data?.code === 401) {
          clearSessionAndRelogin()
          return reject(new Error('登录已过期'))
        }
        if (res.statusCode < 200 || res.statusCode >= 300 || res.data?.code !== 200) {
          return reject(new Error(res.data?.msg || '请求失败'))
        }
        resolve(res.data.data)
      },
      fail: reject
    })
  })
}
```

### 测试 10-1：请求层

- 无 token 的登录请求不发送空 `Bearer `；
- 有 token 自动携带；
- 401 清缓存并只触发一次重登录；
- 业务 500/HTTP 500/超时分别处理；
- 开发、预发、生产 baseURL 可配置；
- 日志不打印 token/code/支付签名。

## 4. 第二步：启动登录

原流程：`uni.login` -> `/auth/h5/login/{code}` -> 保存 token -> `/user/userInfo/getLoginUserInfo` -> 保存 userInfo。

改进：

- 建立全局 auth store 和 `ready` Promise，页面在登录完成前显示 loading；
- code 建议 POST body，避免 URL/访问日志记录；
- 登录失败允许重试，不让页面用空 userInfo；
- token 过期统一刷新登录；
- 不把 openid 当作可展示/可编辑账号。

### 测试 10-2：登录

- 首次登录保存 token/userInfo；
- 再次启动恢复登录态并验证服务端；
- `uni.login` 失败、后端失败、获取用户失败分别展示重试；
- 禁用会员不能进入；
- 页面在 auth ready 前不能扫码。

## 5. 第三步：定位和附近门店

`main` 首页/附近页使用固定北京坐标 `39.984104,116.307503`，没有真实 `wx.getLocation`。复写：

1. 申请 `scope.userLocation`；
2. 用户授权后读取 GCJ-02 坐标；
3. 请求 `/device/device/nearbyStation/{lat}/{lng}`；
4. 空列表展示空状态；
5. 拒绝授权时允许手动选择城市/地址。

### 测试 10-3：位置

- 授权成功传真实 lat/lng；
- 拒绝授权有替代路径；
- 后端空数组不访问 `[0]`；
- marker 点击打开正确站点；
- 站点详情带当前坐标计算距离；
- 地图 key/AppID/域名限制正确。

## 6. 第四步：扫码借用

首页和详情页重复同一流程，抽成 composable：

```text
等待 auth ready
 -> 服务端读取最新押金状态
 -> uni.scanCode
 -> 校验 cabinetNo 格式/签名
 -> GET /device/device/scanCharge/{cabinetNo}
 -> status 1：轮询订单，有限超时
 -> status 2：提示先归还
 -> status 3：跳待支付订单
```

扫码二维码不应只是一段可猜 cabinetNo；推荐签名内容包含 cabinetNo、version、校验码，服务端验证。

轮询必须在 `onUnload/onHide` 清理，并有最大时间（如 30 秒）。设备异步失败时提供“重试/联系客服”，不能永久遮罩。

### 测试 10-4：扫码

- 未免押/未交押金不能借；
- 非法二维码拒绝；
- status 1 后订单出现，跳详情；
- 30 秒无订单停止轮询；
- status 2/3 跳转正确；
- 页面卸载 timer 清零；
- 连点扫码只允许一个请求。

## 7. 第五步：订单列表与详情

订单列表按 pageNum/pageSize 追加。需要维护 `loading/finished/error`，防止触底同时发多个请求。

详情 status=0 显示使用中和实时估算，1 显示待支付按钮，2 显示完成；所有权由后端校验。

### 测试 10-5：订单

- 第一页、下一页、空页、重试；
- 刷新重置列表而非重复追加；
- status 标签/按钮正确；
- A 的 token 不能读取 B 的 id；
- 金额以服务端字符串展示，不在 JS 浮点重算；
- 账单明细和借还位置缺失时有占位。

## 8. 第六步：支付

```text
POST /payment/wxPay/createWxPayment {orderNo}
 -> wx.requestPayment
 -> success 后请求后端 queryPayStatus
 -> 后端确认成功后跳订单列表
```

设置总超时，用户取消不标订单失败。支付参数只用于当前调起，不长期缓存。

### 测试 10-6：支付

- 预支付成功正确映射 5 个参数；
- 用户取消可再次支付；
- 客户端 success 但后端未确认时继续显示“确认中”；
- query 最终 success 跳转；
- 页面卸载停止轮询；
- 非订单所有者/已支付订单后端拒绝；
- 真机实际金额与订单 realAmount 一致。

## 9. 第七步：免押验证

原 `verify` 页面调用 GET `/isFreeDeposit` 即通过，是演示占位。真实流程至少需要：

- 用户授权支付分/押金；
- 服务端创建申请单；
- 微信回调验签；
- 服务端更新 depositStatus；
- 小程序只查询状态，不直接决定状态。

测试伪造回调、重复回调、失败/取消、状态刷新和扫码二次校验。

## 10. 发布前真机检查

- [ ] `urlCheck=true`。
- [ ] request 域名已加入微信后台合法域名且为 HTTPS。
- [ ] 微信支付 notify 公网可达。
- [ ] AppID 与后端 appid/支付商户绑定一致。
- [ ] 地图 key 限制 AppID/接口。
- [ ] 定位权限说明准确。
- [ ] 不提交 secret、私钥、用户 token。
- [ ] 弱网、切后台、杀进程后订单状态能恢复。

> **重点：** 小程序是异步业务的展示端，不是状态真相来源。借出、归还、支付均以服务端和设备事件为准。

