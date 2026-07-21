# 09. Vue3 管理端

## 1. 管理端在架构中的位置

`share-ui` 是若依 Vue3/Vite/Element Plus 管理端。业务页面不直接写完整服务地址：

```text
Vue API wrapper
 -> Axios baseURL=/dev-api
 -> Vite proxy http://localhost:18080
 -> Gateway /device|/user|/rule|/order
 -> 业务服务 Controller
```

生产 `VITE_APP_BASE_API=/prod-api` 需要由 Nginx/网关部署规则处理；仅设置 env 并不会自动产生 `/prod-api` 路由。

## 2. 第一步：依赖和启动

`package.json` 关键依赖：Vue 3.3、Element Plus、Pinia、Vue Router、Axios、ECharts、Vite 5。

```bash
cd share-ui
npm install
npm run dev
npm run build:prod
```

`vite.config.js` 当前开发端口 80，可能需要管理员权限/与其他服务冲突。推荐开发用 5173：

```js
server: {
  port: 5173,
  host: true,
  open: false,
  proxy: {
    '/dev-api': {
      target: 'http://localhost:18080',
      changeOrigin: true,
      rewrite: p => p.replace(/^\/dev-api/, '')
    }
  }
}
```

### 测试 9-1：构建

成功标准：`npm run build:prod` 退出码 0，无 missing export、模板编译或未使用变量导致的错误。`main` 当前地图页导入 `nearbyStation`，但 `src/api/device/station.js` 未导出，必须先修正 API 来源。

## 3. 第二步：请求层

`src/utils/request.js`：

- 自动加 `Authorization: Bearer <token>`；
- GET params 转 query string；
- 1 秒内相同 POST/PUT 防重复；
- code=401 弹重新登录；
- 业务 code 非 200 统一错误；
- 下载 blob。

`main` 把 timeout 改到 50 秒，主要为了 AI 统计，但全局 50 秒会让普通 CRUD 卡很久。推荐默认 10 秒，AI/导出单独设置超时。

### 测试 9-2：请求拦截器

- 登录后携带 Bearer token；
- `headers.isToken=false` 不带 token；
- 401 只弹一次；
- 相同 POST 一秒内第二次拒绝；
- 不同 URL/数据不误判；
- blob 不按 JSON code 解析；
- timeout 展示明确错误。

## 4. 第三步：先做接口契约表

逐页写 API wrapper 前，先核对后端。`main` 当前状态：

| 页面 | 前端 API | 后端 | 结论 |
|---|---|---|---|
| 柜机 | `/device/cabinet/*` | `CabinetController` | 基本匹配 |
| 柜机类型 | `/device/cabinetType/*` | `CabinetTypeController` | 匹配 |
| 充电宝 | `/device/powerBank/*` | `PowerBankController` | 匹配 |
| 站点 | `/device/station/*` | `StationController` | 基本匹配 |
| 地区 | `/device/region/treeSelect/*` | `RegionController` | 匹配 |
| 地址解析 | `/device/map/calculateLatLng/*` | `MapController` | 匹配 |
| 地图附近站点 | 导入缺失 | `DeviceController` | 构建失败，需补 wrapper |
| 费用规则 | `/rule/feeRule/*` | `FeeRuleController` | edit 原后端假成功 |
| 会员 | `/user/userInfo/*` | `UserInfoController` | list Mapper SQL 缺失 |
| 订单 | `/order/orderInfo/list|/{id}|userList|statistics` | 无对应 Controller | 不匹配 |
| orderBill/orderLog | 生成的 CRUD wrapper | 无 Controller | 未使用残留 |
| AI统计 | `/sta/*` | `share-stastics` | 有接口但原始 SQL危险 |

**为什么先做表：** 页面显示错误不一定是 Vue 问题；如果后端路径根本不存在，继续改页面只会掩盖契约缺口。

### 契约测试 9-3

对每个 wrapper 用 Mock Service Worker/Vitest：断言 method、URL、query/body。后端用 MockMvc 生成实际 mapping 清单，在 CI 比较。至少保证页面使用的每个 wrapper 有 provider。

## 5. 第四步：设备页面

推荐实现顺序：柜机类型 -> 充电宝 -> 柜机 -> 站点 -> 地图。因为站点依赖柜机和规则选择。

每个列表页要有：查询、重置、分页、loading、空状态、权限按钮、新增/编辑表单校验、删除确认、接口失败状态。

`main` 已知问题：

- station 图片上传回调写 `form.logo`，实体字段是 `imageUrl`；
- map 页面把 station id 传给柜机详情，应传 `row.cabinetId`；
- powerBank 编辑弹窗标题误写“商品单位”；
- 后端部分管理 Controller 无权限，前端按钮隐藏不能代替服务端鉴权。

### 测试 9-4：设备页面

- 新增后列表刷新且数据库有记录；
- 编辑后真实落库，不只 toast 成功；
- 删除有引用柜机时显示后端业务错误；
- 图片上传回写 `imageUrl` 并可预览；
- 空站点地图不访问 `list[0]`；
- 第三方地图加载失败有备用列表；
- 无权限按钮不显示，直接发请求也被后端 403。

## 6. 第五步：动态菜单

若依管理端不是在前端静态写全部业务路由，而是登录后调用 `/system/menu/getRouters`，根据 `sys_menu.component` 加载 Vue 页面。

仓库 `sql/share-system.sql` 没有完整设备、规则、订单、统计菜单。需要新增菜单迁移，component 路径要与文件一致，例如：

```text
device/cabinet/index
device/cabinetType/index
device/powerBank/index
device/station/index
map/station/index
rule/feeRule/index
user/userInfo/index
order/orderInfo/index
statistics/order/index
statistics/region/index
```

按钮权限必须与 Controller `RequiresPermissions` 一致。

### 测试 9-5：菜单

- admin 登录看到业务菜单；
- 只读角色只有 list/query，没有 add/edit/remove；
- component 路径能动态 import；
- 直接输入无权限 URL 被路由守卫拒绝；
- 刷新页面后动态路由仍恢复。

## 7. 第六步：订单和统计页面

先补后端真实接口，再写页面。订单页只读为主：订单状态、借还位置、时长、规则快照、金额、支付号、账单。不要开放通用“编辑订单状态”。

`views/statistics/*` 与 `views/stastics/*` 是两套页面：前者固定订单/地区报表，后者实验性 AI/会员图表。复写时统一命名为 `statistics`，先实现固定指标。

### 测试 9-6：订单页面

- status=0/1/2 标签准确；
- 金额用两位小数，不做 JS 浮点计算；
- 详情显示账单和用户摘要；
- 用户过滤走后端参数，不前端全量过滤；
- 空统计返回空图/0，而不是报错；
- 大分页和慢请求可取消，切页不显示旧响应。

## 8. 地图安全

地图页将数据库内容拼进 `InfoWindow.content` HTML，会产生存储型 XSS。不要把 station.name/address 直接拼字符串；使用框架模板或先严格 HTML escape。

测试插入：

```text
站点名：<img src=x onerror=alert(1)>
```

预期只显示文本，不执行脚本。腾讯地图 key 应限制调用域名和 API 配额。

## 9. 本阶段完成标准

- [ ] `npm run build:prod` 通过。
- [ ] API wrapper 与 Controller 一一对应，无生成残留误导。
- [ ] 动态菜单 SQL 和权限码完整。
- [ ] 设备/规则/会员页面的新增编辑确实落库。
- [ ] 订单管理只通过状态机，不开放任意改状态。
- [ ] 地图 XSS、空数据、第三方失败有测试。
- [ ] 全局 timeout 不再为 AI 粗暴放大。

> **注意：** 前端的 `v-hasPermi` 只改善界面，真正安全边界始终在后端权限注解和所有权校验。

