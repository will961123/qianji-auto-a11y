# AGENTS.md

给接手本项目的智能体/开发者的工作说明。先读这份，再读 `README.md`。

## 项目一句话

Android 无障碍服务，读支付/账单详情页文字 → 弹面板确认 → 通过 `qianji://publicapi/addbill` 提交到「钱迹」。

包名 `com.dsh.qianji_auto_a11y`，应用名「钱迹无障碍记账插件」，语言 Kotlin + 原生 View（无 Compose）。

## 快速命令

```powershell
.\build.ps1              # 构建 debug APK
.\build.ps1 -Install     # 构建并安装（安装后无障碍会被 MIUI 关闭，需手动重开）
.\build.ps1 -Clean       # clean 后构建

# 目标设备（若不在 PATH）
adb logcat -s AutoQianji
adb shell uiautomator dump /sdcard/ui.xml; adb pull /sdcard/ui.xml .
adb exec-out run-as com.dsh.qianji_auto_a11y cat databases/auto.db > auto.db
```

工具链优先用仓库内 `tools/`（jdk21 / android-sdk / gradle-8.9），否则用 `JAVA_HOME`、`ANDROID_HOME`。

## 代码地图

| 文件 | 职责 |
| --- | --- |
| `AutoRecordService.kt` | 无障碍服务主体：事件过滤 → 工作线程探测 → 解析 → 稳定性校验 → 弹窗/提交。**改动最频繁** |
| `NodeScan.kt` | 解析流水线：时间 → 金额 → 商户 → 优惠/原价 → 类型 → 分类 → 资产。含各种启发式 |
| `PayRules.kt` | 读 `assets/pay_rules.json`，按命中特征词挑场景；`supportedPackages()` 决定监听哪些包 |
| `OverlayBill.kt` | 悬浮面板：主面板、资产/分类选择、时间、备注、列表、重复提示，全部 `TYPE_ACCESSIBILITY_OVERLAY` |
| `Submitter.kt` | 拼 `qianji://publicapi/addbill` 的 query 参数 |
| `Store.kt` | SQLite（logs/assets/categories/books/rules）、关键词与资产匹配、日志读写 |
| `Config.kt` | SharedPreferences 开关与默认值 |
| `Importer.kt` + `XlsxReader.kt` | 读钱迹导出的 xlsx/csv/json，抽资产/分类/账本 |
| `Backup.kt` | 本应用数据的 JSON 导出/导入（勾选式，导入为整表覆盖） |
| `Builtin.kt` | 出厂规则与内置分类（`其它` 不可删） |
| `MainActivity.kt` | 首页 + 设置页（设置页很长，注意分组顺序） |
| `LogActivity.kt` / `EntrySheet.kt` | 记录列表与详情弹窗 |
| `LookupActivity.kt` / `LookupAdapter.kt` | 资产/分类/账本管理（分组卡片 + 拖拽排序 + 长按菜单） |
| `ManualBillActivity.kt` | 手动记一笔 |
| `Stability.kt` / `StabilityActivity.kt` | 权限检查、从最近任务隐藏（`AppTask.setExcludeFromRecents`） |
| `Common.kt` / `PageHeader.kt` / `StickyHeader.kt` / `EmptyState.kt` / `GlyphIcon.kt` / `DatePick.kt` | 通用 UI 组件 |

## 关键不变量

1. **规则驱动，不要写死 App 逻辑**。新的支付场景加在 `pay_rules.json`；新的解析能力加通用字段（如 `amountIsNet`、`amount`、`merchantSuffix`、`timeOptional`、`couponEnabled`、`type`、`account2`），不要针对某个 App 写分支。
2. **解析顺序固定**（见 `NodeScan.parse`）：先 `scopeNodes` 收窄到详情卡片，再取时间、金额、商户、优惠/原价、类型、分类、资产。改顺序容易破坏已有场景。
3. **优先「结构」而非「标签名」**：优惠行常改叫法（立减/抵价券/支付遇财神），现在是「已知标签 → 值为负数的行 → 原价-实付倒推」三级兜底。
4. **类型决定表单**：`type == 2 || 3`（转账/还款）时隐藏分类、显示「转出/转入」，分类留空；列表里也按这个规则渲染。
5. **悬浮窗必须用 `TYPE_ACCESSIBILITY_OVERLAY`**（`OverlayBill.overlayType`）。`TYPE_APPLICATION_OVERLAY` 在 MIUI 上 `Settings.canDrawOverlays()` 可能返回 false 且不报错，表现为「弹窗不出现」。
6. **提示抑制按「这一笔」**：`dismissedPages` 的 key 是 `包名|窗口|金额|时间`；切换前台应用时清空（忽略 systemui/桌面/截图/自身包名），避免最小化、下拉通知栏、截图后重复弹窗。
7. **稳定性门闸**：同一页面要连续两次解析结果一致才弹窗（`lastSignature`），并有基本 sanity 校验（商户不能等于付款方式、备注要包含商户等）。
8. **数据库无迁移**：`DB_VERSION = 1`，`migrations` 为空时 `onUpgrade` 直接重建。要保住数据时：`DB_VERSION + 1` 并在 `migrations` 里补 `2 to { db -> db.execSQL("ALTER TABLE ...") }`。
9. **提交参数**：`type, money, time, remark, catename, accountname, accountname2, coupon, bookname, fee, showresult`；分类路径用 `一级/::/二级`；`money` 用原价、`coupon` 用优惠。
10. **主题走 token**：`app_bg / card_bg / text_primary / text_secondary / icon_primary / icon_tertiary / divider / accent / on_accent / danger / tag_*`，`values` 与 `values-night` 各一份，不要在新布局里写死颜色。

## 调试与验证

- 设备上**不能用 `adb shell input`**（缺 `INJECT_EVENTS`），只能 `screencap` + `uiautomator dump` 观察，或让用户手动操作。
- 需要临时打开某个 Activity 时，可临时给它加 `android:exported="true"` + `am start`，**验证完必须改回来并重新构建**（历史上多次忘记回滚）。
- 面板样式可以临时用一个 `PreviewActivity`（inflate `overlay_sheet.xml`）截图核对，同样用完即删。
- 安装后无障碍服务必然被关闭：验证前先确认 `adb shell dumpsys accessibility | grep 钱迹` 有输出，否则什么都不会触发。
- 数据库操作很危险：曾因 `onUpgrade` 里同时 ALTER 和重建导致日志清空。改 `Store` 的建表/迁移逻辑前先备份 `auto.db`。

## 本机开发注意

- 工程放在含中文的路径下时，AGP 会拒绝构建，`gradle.properties` 里的 `android.overridePathCheck=true` 不能删。
- `gradle.properties` 里有本地代理配置（127.0.0.1:7890），换网络环境要删掉，否则依赖拉不下来。
- 改文件优先用编辑器/脚本（Python 处理多行替换更稳），PowerShell 的字符串替换在 CRLF 文件上容易匹配失败或写坏文件。
- 构建时 `TEMP`、`GRADLE_USER_HOME` 都被 `build.ps1` 指到仓库内，便于清理，不要把这两个改回系统目录（曾有路径权限问题）。

## 改动检查清单

- [ ] `.\build.ps1` 通过，无编译警告级错误
- [ ] 改动涉及解析：用真实页面验证 `draft ...` 日志的 amount / coupon / merchant / type / cate / account 都对
- [ ] 改动涉及 UI：浅色 + 深色各看一眼
- [ ] 临时 hook / `exported="true"` / PreviewActivity 已删除（`grep -r "backupDemo\|sheetDemo\|PreviewActivity" app/src`）
- [ ] 数据库相关：确认没有意外重建或清空数据
- [ ] 提交前提醒用户：重新开启无障碍服务
