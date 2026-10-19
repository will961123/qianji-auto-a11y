# qianji-auto-a11y

Android 无障碍自动记账插件：读取支付/账单详情页的文字，弹窗确认后一键记到「钱迹」。

> 项目名取自三个关键词：**qianji**（钱迹）、**auto**（自动记账）、**a11y**（accessibility，无障碍）。
> 应用名：钱迹无障碍记账插件；包名：`com.dsh.qianji_auto_a11y`。

## 已验证环境

| 项目 | 状态 |
| --- | --- |
| 小米 HyperOS 3 / Android 16 | ✅ 已在真机调试验证 |
| 其他 ROM、其他 Android 版本 | ⚠️ 未验证，按 Android 标准无障碍实现编写，理论上可用 |

开发与验证只在小屏手机（1440×3200）横竖屏中的竖屏形态下进行过，平板、折叠屏未验证。

## 功能

- **自动识别账单页**：无障碍服务采集页面文字，按「支付 App + 页面场景」匹配规则，提取金额、商户、时间、分类、资产
- **确认后提交**：识别到账单后弹出半屏面板，可改金额/类型/分类/资产/优惠/时间/备注，确认后通过 `qianji://publicapi/addbill` 提交
- **场景规则**：内置支付宝、微信支付、云闪付、京东四类 App 的页面规则，规则写在 `app/src/main/assets/pay_rules.json`，不需要改代码
- **匹配规则**：关键词 → 分类、付款方式 → 资产，支持用户自定义，内置一份出厂规则
- **手动记账**：悬浮按钮/通知栏入口手动记一笔
- **数据管理**：资产、分类（两级）、账本维护；从钱迹导出的 Excel 导入；本应用数据的 JSON 导出/导入
- **本地记录**：识别到的每一笔都先落地本地，可查看、重发、删除
- **深色模式**：跟随系统

## 环境要求

| 依赖 | 版本 |
| --- | --- |
| JDK | 21 |
| Android SDK | compileSdk 35 / minSdk 30 |
| Gradle | 8.9 |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |

## 目录结构

```
qianji-auto-a11y/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/pay_rules.json      # 各支付 App 的页面场景规则
│       ├── java/com/dsh/qianji_auto_a11y/   # 全部 Kotlin 源码
│       └── res/                       # 布局、图标、主题、无障碍配置
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── build.ps1                          # 一键构建（可选安装到设备）
└── build.cmd                          # Windows 双击入口
```

## 构建

### 1. 准备工具链

两种方式任选：

**A. 仓库内 `tools/`（推荐，方便离线）**

```
tools/
├── jdk21/
├── android-sdk/
└── gradle-8.9/
```

**B. 使用系统环境变量**

设置 `JAVA_HOME`、`ANDROID_HOME`，并保证 `gradle` 在 PATH 里。

### 2. 构建

```powershell
# Debug APK
.\build.ps1

# 先 clean 再构建
.\build.ps1 -Clean

# 构建并安装到已连接的设备（adb 可用时）
.\build.ps1 -Install
```

或双击 `build.cmd`。

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 3. 打包正式版

```powershell
$env:JAVA_HOME = "tools\jdk21"
$env:ANDROID_HOME = "tools\android-sdk"
tools\gradle-8.9\bin\gradle.bat --no-daemon :app:assembleRelease
```

默认未配置签名，`assembleRelease` 产出的是未签名 APK。需要正式分发时，在 `app/build.gradle.kts` 里补 `signingConfigs`，签名文件与口令不要入库（见 `.gitignore`）。

## 安装与首次配置

1. `adb install -r app-debug.apk`，或直接在文件管理器里安装
2. 打开 App → 设置 → **系统无障碍设置** → 找到「钱迹无障碍记账插件」→ 开启
3. 设置 → **悬浮窗权限** → 授权（识别到账单后的确认面板要用）
4. 设置 → **系统优化** → 按提示处理电池优化/自启动，避免后台被清理
5. 设置 → **数据与规则** → 从钱迹导入 Excel（可选，用于准备资产/分类/账本）
6. 按需维护分类匹配规则、资产匹配规则、默认值

> ⚠️ **每次覆盖安装 APK，MIUI 都会自动关闭无障碍服务**，需要重新去系统设置里打开。

## 调试

### 看识别过程

```powershell
adb logcat -s AutoQianji
```

关键日志：

```
probe pkg=com.tencent.mm rootPkg=com.tencent.mm nodes=13 app=微信支付 rule=微信·扫码支付 time=... amounts=[...]
draft amount=25.50 net=25.48 merchant=京东外卖 type=0 cate=电器数码 account=中信银行信用卡 coupon=0.02 ...
prompt show key=com.unionpay|1921.55|中信银行信用卡|2026-10-16 11:41:13
```

- `rule=` 是命中的场景规则，`rule=无` 表示页面特征词没匹配上
- `unknown-detail pkg=... texts=[...]` 表示页面像账单详情但没有规则命中，可以据此补规则
- 日志里的 `amount` 是原价、`net` 是实付、`coupon` 是优惠

### 看页面结构

```powershell
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml .
```

安卓的高版本对 `adb shell input` 限制较多（需要 `INJECT_EVENTS`），一般只能靠 `screencap` + `uiautomator dump` 观察，不能脚本点击。

### 数据库

```powershell
# 拉取（debug 包可直接 run-as）
adb exec-out run-as com.dsh.qianji_auto_a11y cat databases/auto.db > auto.db
```

用任意 SQLite 工具打开即可查看 `logs / assets / categories / books / rules`。

## 识别规则（pay_rules.json）

```json
{
  "apps": [
    {
      "name": "微信支付",
      "packages": ["com.tencent.mm"],
      "rules": [
        {
          "id": "微信·扫码支付",
          "marks": ["扫二维码付款", "收款方备注", "转账单号"],
          "merchant": ["商户全称", "商户名称"],
          "desc": ["商品说明"],
          "time": ["支付时间"],
          "method": ["支付方式", "付款方式"],
          "category": ["账单分类"],
          "price": ["订单金额"],
          "coupon": ["优惠", "立减"],
          "asset": ["微信"],
          "account2": ["信用卡号"],
          "amount": ["实付款"],
          "merchantSuffix": ["旗舰店"],
          "type": 3,
          "amountIsNet": true,
          "timeOptional": true,
          "couponEnabled": false,
          "defaultCate": "收红包/::/微信收红包",
          "income": ["已存入零钱"],
          "expense": ["转给"]
        }
      ]
    }
  ]
}
```

| 字段 | 含义 |
| --- | --- |
| `marks` | 页面特征词，命中越多越优先；一个 App 有多条规则时靠它区分场景 |
| `merchant` / `desc` / `time` / `method` / `category` / `price` / `coupon` / `account2` / `amount` | 对应字段的标签名 |
| `merchantSuffix` | 没有商户字段时，按「旗舰店 / 专营店」这类后缀找店铺名 |
| `asset` | 默认资产（在「付款方式」推断失败后用） |
| `income` / `expense` | 方向特征词 |
| `type` | 固定账单类型：0 支出 / 1 收入 / 2 转账 / 3 还款 / 5 报销 |
| `defaultCate` | 关键词规则没命中时的兜底分类 |
| `amountIsNet` | 页面大金额是「优惠后」的金额（京东这类），原价 = 金额 + 优惠 |
| `timeOptional` | 页面没有时间字段时用当前时间 |
| `couponEnabled` | 是否识别优惠，默认开启 |

`packages` 里的包名同时决定无障碍监听范围——没在规则表里的 App 不会被读取。

## 数据导入导出

- **从钱迹导入**：读钱迹「数据导出」的 Excel，取 `账本 / 分类 / 二级分类 / 账户1 / 账户2` 几列
- **导出数据 / 导入数据**：本应用自己的 JSON 备份，可勾选分类匹配规则、资产匹配规则、默认值、资产列表、分类列表、账本列表、记账记录（默认不勾）
- 导入是**完整覆盖**：勾了哪一项，就把那一项整体替换成文件里的内容

## 已知限制

- **只验证过一台小米 HyperOS 3 设备**，其他系统的悬浮窗、无障碍事件行为可能有差异
- **页面文字必须能被无障碍读到**：抖音这类自绘页面（Lynx）几乎不暴露文本，无法识别，已从规则表移除
- **支付保护 / 自启动 / 后台弹出界面** 这类权限没有公开 API，只能提示用户手动处理
- 每次覆盖安装后需要重新开启无障碍服务
- 数据库还没有正式迁移机制：表结构改动会整库重建（见 `AGENTS.md`）
