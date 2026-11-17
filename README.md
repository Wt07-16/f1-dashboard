# F1 Dashboard

一款基于 **Java 26** 与 **JavaFX** 构建的 F1 赛车实时赛况与数据分析桌面应用。采用**端口与适配器（Ports & Adapters / 六边形架构）**实现业务领域、多数据源与 UI 表现层的严格解耦，支持**五种比赛场次实时遥测（OpenF1）**、**历史正赛逐圈变速回放**、**离线模拟比赛**、**车手/车队积分榜与个人轨迹**、**单圈用时折线对比**以及**多源并发新闻聚合**。

---

## 1. 项目简介

F1 观赛过程中，车迷往往需要在多个网站之间切换才能同时查看实时名次变动、历史单圈对比、赛历倒计时与赛事新闻。本项目将上述核心需求整合为统一的深色主题桌面看板：

- **面向工程解耦**：全项目不依赖重型框架，使用纯 Java 手动实现**组合根依赖注入（DI）**、**异步线程池调度**、**带 TTL 的并发内存缓存**与**多数据源热切换**。
- **全天候可演示**：无论当前是否处于 F1 比赛直播时段、甚至在完全断网的环境下，均可通过内置的**历史逐圈回放引擎**或**本地随机比赛模拟器**完整体验所有核心链路。

---

## 2. 项目核心功能

| 模块 | 核心功能说明 |
|---|---|
| ⚡ **实时排名** (`ui/live`) | 共用同一套响应式表格与状态机，支持三种数据流无缝切换：<br>1. 🛰️ **OpenF1 实时流**：覆盖一练/二练/三练（FP1/2/3）、冲刺排位赛（SQ）、冲刺赛（Sprint）、排位赛（Qualifying）、正赛（Race）全部五种场次；排位/练习按最快圈展示，正赛/冲刺按赛道名次展示，自动识别替补/新秀车手；<br>2. 🎞️ **历史真实回放**：拉取已完赛站次的真实逐圈记录（~1000+ 条/站），支持 **0.5× / 1× / 2× / 4×** 变速重放，退赛与被套圈完赛严格按官方成绩裁决；<br>3. 🎬 **Mock 离线演示**：纯本地模拟一场约 75 秒的动态超车与退赛过程，零网络依赖。 |
| 🏆 **积分榜** (`ui/standings`) | 车手积分榜 / 车队积分榜切换，`CompletableFuture` **双榜并行拉取**；支持按轮次回溯任意已完赛站次的赛后积分；点击任意车手行弹出**个人资料卡**（车号、国籍、年龄、车队及近 5 站完赛名次轨迹）。 |
| 🗓️ **赛历** (`ui/calendar`) | 全季 23 站赛程概览，顶部焦点大卡提供下一站正赛 **毫秒级走秒倒计时**（`AnimationTimer`）；已完赛站次支持点击弹窗查看完整正赛成绩单。 |
| 📈 **圈速对比** (`ui/charts`) | 任选已完赛分站与 1–2 名车手，将整场 50+ 圈的单圈用时绘制为双色叠加折线图（直观呈现进站圈掉速、新胎推演与退赛断线），支持点击图例单独显隐曲线。 |
| 📰 **新闻** (`ui/news`) | 独立守护线程池并发拉取 **F1 官方 RSS** 与 **Motorsport RSS**，DOM 解析 XML 后按发布时间倒序聚合，点击标题调用系统浏览器打开原文。 |
| 🔔 **桌面通知与设置** (`ui/notify`, `ui/settings`) | 系统菜单栏托盘图标（红白方格旗）；支持**开赛前 90 分钟自动提醒**与**实时榜前三名易手横幅通知**（带 12 秒防抖节流）；设置页支持 REAL / MOCK 数据源热切换与 OpenF1 Sponsor 凭据配置及连通性测试。 |

---

## 3. 实际技术栈

| 类别 | 实际采用技术 | 用途说明 |
|---|---|---|
| **语言与运行时** | **Java 26** | 广泛使用 `record` 不可变数据模型、Switch 表达式、Lambda 与方法引用 |
| **图形界面** | **JavaFX 26.0.2** | 纯 Java 代码构建视图树（无 FXML），配合 `theme.css` 实现深色主题 |
| **网络通信** | **JDK `java.net.http.HttpClient`** | 统一封装超时控制、重定向、Bearer Token 认证与 HTTP 状态码异常转换 |
| **数据解析** | **Jackson 2.22.2** + **JDK DOM XML** | JSON 反序列化（忽略未知字段、大小写容错）与 RSS XML 安全解析 |
| **并发与异步** | **JUC (`ScheduledExecutorService`, `CompletableFuture`, `ConcurrentHashMap`, `AtomicBoolean`)** | 后台定时轮询、多接口并行拉取、UI 帧合并刷新（Batch Drain）、线程安全 TTL 缓存 |
| **构建与打包** | **Maven** + **JDK `jpackage` / `iconutil`** | 依赖管理、编译构建，以及生成自带 Java 运行时与 `.icns` 矢量图标的 macOS `.app` |

---

## 4. 项目目录结构

```text
F1 application/
├── pom.xml                                  # Maven 构建配置 (Java 26 + JavaFX 26.0.2 + Jackson 2.22.2)
├── README.md                                # 项目文档
├── 打开 F1 Dashboard.command                # macOS 终端快捷启动脚本
├── packaging/                               # 原生应用打包工具集
│   ├── MakeIcon.java                        #   Java2D 矢量绘制 16px~1024px 红白方格旗图标
│   ├── make-icon.sh                         #   图片/内置设计转 macOS .icns 脚本
│   └── build-app.sh                         #   一键编译、收集平台 jar 并调用 jpackage 生成 .app
└── src/main/
    ├── resources/f1/ui/theme.css            # 全局深色主题 CSS 样式表
    └── java/f1/                             # 核心源码 (共 51 个 Java 类)
        ├── app/                             # 【组合根与装配层】
        │   ├── Main.java                    #   独立引导入口(规避 JavaFX 模块启动限制)
        │   ├── F1Application.java           #   JavaFX 宿主、共享线程池、开赛提醒调度、防死锁退出
        │   ├── F1Session.java               #   全局会话门面:数据源路由、TTL 内存缓存、启动预热
        │   └── AppConfig.java               #   本地 JSON 配置持久化 (~/.f1-dashboard/config.json)
        ├── domain/                          # 【核心领域层 —— 纯 Java 零外部依赖】
        │   ├── DataException.java           #   统一数据访问异常
        │   ├── model/                       #   10 个不可变领域模型 (Driver, Team, RaceEntry, Session...)
        │   └── port/                        #   6 个数据源契约接口 (SeasonProvider, StandingsProvider, LiveTimingProvider...)
        ├── data/                            # 【数据基础设施层 —— 实现 domain/port 接口】
        │   ├── http/Http.java               #   统一 HTTP GET/JSON 封装
        │   ├── openf1/                      #   OpenF1 实时流实现 (OpenF1Client, OpenF1LiveProvider, OpenF1Dtos)
        │   ├── jolpica/                     #   Jolpica 真实数据源实现 (赛历/积分榜/分页逐圈合并/DTO & Mapper)
        │   ├── rss/RssNewsProvider.java     #   多源并发 RSS 新闻拉取与 XML 解析
        │   └── mock/                        #   离线快照底座 (MockSnapshot)、模拟赛引擎、真实数据变速回放引擎
        ├── ui/                              # 【表现层 —— 仅依赖 domain 接口与 F1Session】
        │   ├── RootView.java & F1Page.java  #   侧边栏导航容器与页面生命周期接口 (build / onShown / onHidden)
        │   ├── live/LivePage.java           #   实时排名页 (含帧合并分发与场次自动巡查)
        │   ├── standings/                   #   积分榜页 (StandingsPage) 与车手资料卡弹窗 (ProfileDialog)
        │   ├── calendar/CalendarPage.java   #   赛历倒计时与单站成绩弹窗
        │   ├── charts/LapChartsPage.java    #   双车手圈速折线对比页
        │   ├── news/NewsPage.java           #   新闻列表页
        │   ├── settings/SettingsPage.java   #   设置页 (数据源热切换、OpenF1 凭据管理与连接测试)
        │   ├── notify/Notifier.java         #   AWT 系统托盘与桌面通知封装
        │   └── util/                        #   Async (后台任务切回 FX 线程) 与 StateCard (状态占位卡)
        └── util/Json.java                   # 全局 Jackson ObjectMapper 单例配置
```

---

## 5. 系统架构说明

本项目遵循**端口与适配器（六边形架构）**与**依赖倒置原则（DIP）**，全项目保持严格的单向依赖：

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                              表现层 (ui/)                               │
│  LivePage  StandingsPage  CalendarPage  LapChartsPage  NewsPage  Settings│
└──────────────────┬───────────────────────────────────▲──────────────────┘
                   │ 1. Async.run() 异步调用门面        │ 6. Platform.runLater()
                   ▼                                   │    切回 FX 线程刷新 UI
┌──────────────────────────────────────────────────────┴──────────────────┐
│                        应用装配与缓存门面 (app/)                        │
│  F1Application (线程池/生命周期)  ───►  F1Session (TTL 内存缓存 + 路由) │
└──────────────────┬──────────────────────────────────────────────────────┘
                   │ 2. 面向接口调用 (仅依赖 domain/port)
                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     核心领域层 (domain/ —— 纯 Java 零依赖)               │
│   port 接口: SeasonProvider | StandingsProvider | LiveTimingProvider... │
│   model 模型: Driver | Team | RaceEntry | Session | PositionUpdate...   │
└──────▲────────────────────────▲──────────────────────▲──────────────▲───┘
       │ 3. implements          │                      │              │
┌──────┴─────────┐    ┌─────────┴────────┐   ┌─────────┴──────┐  ┌────┴────────┐
│ data/jolpica/  │    │  data/openf1/    │   │   data/rss/    │  │ data/mock/  │
│ 赛历/积分/逐圈 │    │ 5种场次实时流+认证│   │ 多源并发XML聚合 │  │离线快照/回放│
└────────────────┘    └──────────────────┘   └────────────────┘  └─────────────┘
```

### 关键工程设计亮点

1. **防腐层隔离（DTO -> Mapper -> Domain Model）**：第三方接口的原始 JSON 结构被严格限制在 `data/*/dto/` 内，由 `Mapper` 容错转换为不可变的 `domain/model` 记录类，外部字段变更不波及业务与 UI。
2. **UI 帧合并刷新（Batch Drain）**：实时排名引擎在工作线程高频推送 `PositionUpdate` 时，`LivePage` 通过同步缓冲队列 `pending` + `draining` 标志位，将同一 JavaFX 渲染拍内的多批更新合并为单次表格排序与重绘，避免 UI 线程阻塞。
3. **macOS 跨工具包退出防死锁**：针对 macOS 上 JavaFX 主线程与 AWT `SystemTray` 混用时 `SystemTray.remove()` 引起的 AppKit 主线程死锁，采用“先隐藏窗口 + 守护线程限时清理（500ms Grace Period）+ `Runtime.halt(0)` 兜底”的退出机制，确保点击关闭按钮 200ms 内干净退出。

---

## 6. 数据来源及获取方式

| 数据域 | 数据源 | 获取与处理方式 |
|---|---|---|
| **全场次实时遥测** | [OpenF1 API](https://api.openf1.org/v1) | `OpenF1LiveProvider` 每 5 秒增量轮询 `/v1/position`（按 `date>` 过滤）与 `/v1/laps`（按 `lap_number>=` 过滤）。非直播时段免费免认证；比赛直播窗口（开赛前 30 分钟 ~ 结束后 30 分钟）通过 `OpenF1Client` 向 `/token` 自动换取并续期 OAuth2 Bearer Token。 |
| **赛历 / 积分榜 / 历史逐圈** | [Jolpica F1 API](https://api.jolpi.ca) (Ergast 继任者) | 免费公开 REST 接口。其中 `/laps.json` 单页上限 100 条，`JolpicaLapHistoryProvider` 自动循环分页拉取并按圈号合并跨页车手记录。 |
| **赛事新闻** | F1 官方 RSS & Motorsport RSS | `RssNewsProvider` 使用独立定长线程池 + `CompletableFuture` 并行请求两个 XML 订阅源，启用 `FEATURE_SECURE_PROCESSING` 防御 XXE，按发布时间倒序归并。 |
| **离线演示与车手底座** | 内置 `MockSnapshot` | 内嵌 2026 赛季 23 站赛历骨架与第 13 站（Monza）赛后真实车手/车队积分快照，离线立即可用。 |

---

## 7. 数据存储与缓存设计

本项目定位为轻量级实时看板，**不依赖外部 MySQL 数据库**，采用 **“本地 JSON 文件持久化 + 多级 `ConcurrentHashMap` TTL 内存缓存”** 的双层存储设计：

### 7.1 本地持久化存储（`~/.f1-dashboard/config.json`）

由 `AppConfig` 负责读写（线程安全 `synchronized save()`），结构如下：

| 字段名 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `source` | `Enum (REAL / MOCK)` | `REAL` | 当前核心数据源模式 |
| `notifyEnabled` | `boolean` | `true` | 系统托盘桌面通知总开关 |
| `openf1Username` | `String` | `""` | OpenF1 Sponsor 订阅账号邮箱（存于用户主目录，不进 Git 仓库） |
| `openf1Password` | `String` | `""` | OpenF1 Sponsor 订阅密码 |
| `openf1Token` | `String` | `""` | 可选直填 Bearer Token |

### 7.2 内存缓存设计（`F1Session`）

为消除页面切换时的重复网络等待，并配合应用启动后的后台预热（`warmUp()`），在 `F1Session` 中维护以下线程安全缓存（支持通过页面「↻ 刷新」按钮传入 `force = true` 强制穿透刷新）：

| 缓存容器 | Key 类型 | Value 类型 | TTL 过期时间 | 设计考量 |
|---|---|---|---|---|
| `scheduleCache` | 单例 (`volatile`) | `Timed<List<RaceEntry>>` | **5 分钟** | 赛历极少变动，供积分榜、赛历页、实时页巡查共享 |
| `driverStandingsCache` | `Integer` (轮次 `round`) | `Timed<List<DriverChampRow>>` | **60 秒** | 与 `teamStandingsCache` 配合 `CompletableFuture` 并行加载，重复切页 0ms 响应 |
| `teamStandingsCache` | `Integer` (轮次 `round`) | `Timed<List<TeamChampRow>>` | **60 秒** | 车队积分榜按轮次缓存 |
| `newsCache` | 单例 (`volatile`) | `Timed<List<NewsArticle>>` | **60 秒** | 缓存合并后的全量新闻，按页面 `limit` 切片返回 |
| `resultsCache` | `Integer` (轮次 `round`) | `CacheEntry` (`List<FinishingPosition>`) | **30 分钟** | 已完赛站正式成绩固定不变，供赛历弹窗与车手近 5 站轨迹复用 |
| `lapsCache` | `Integer` (轮次 `round`) | `LapCacheEntry` (`List<LapSample>`) | **30 分钟** | 整站 ~1000 条逐圈数据需分页拉取，缓存后切换对比车手无需重拉网络 |

---

## 8. 本地运行步骤

### 环境要求
- **JDK 26**（或更高版本）
- **Maven 3.8+**（若本机未独立安装 Maven，可直接使用 IntelliJ IDEA 内置 Maven）

### 方式一：命令行直接运行
```bash
# 克隆仓库
git clone https://github.com/Wt07-16/f1-dashboard.git
cd f1-dashboard

# 启动应用
mvn javafx:run
```

### 方式二：IntelliJ IDEA 运行
1. `File -> Open` 选择项目根目录下的 `pom.xml`；
2. 等待 Maven 自动下载 JavaFX 26.0.2 与 Jackson 2.22.2 依赖；
3. 运行主类 `f1.app.Main`。

### 方式三：打包为 macOS 原生应用（`.app`）
```bash
# 编译、生成矢量图标并打包安装到 ~/Applications 与桌面
./packaging/build-app.sh --install --desktop
```

---

## 9. 项目演示方式

即使当前没有正在进行的 F1 比赛，你也可以通过以下三种方式立即验证所有功能：

1. **体验 OpenF1 真实赛况数据**：打开「⚡ 实时排名」页，点击顶部 **「🛰️ OpenF1 实时」** 按钮 —— 非直播时段会自动拉取最近一场真实场次（练习/排位/正赛）的车手名次与最快单圈。
2. **体验真实分站逐圈变速回放**：点击 **「🎞️ 回放最近一站」**，出现倍速条后可切换 `0.5× / 1× / 2× / 4×`，观察真实比赛每一圈的名次升降箭头、退赛灰显（⛔）以及前三名变动通知。
3. **体验离线模拟赛**：点击 **「🎬 Mock 演示」**（或在「⚙️ 设置」中切换为 `Mock 演示`），完全断网也可运行。

---

## 10. 后续开发计划 (Roadmap)

- [ ] **离线落盘缓存**：将最近一次成功拉取的赛历、积分榜与新闻快照序列化至 `~/.f1-dashboard/cache/`，实现断网冷启动秒开。
- [ ] **单元测试体系**：引入 JUnit 5，为核心解析器（`JolpicaMapper`、圈速格式化、分页逐圈合并、退赛判定）补充自动化测试。
- [ ] **OpenF1 流式推送升级**：在现有 REST 增量轮询基础上，增加基于 WebSocket / MQTT（`wss://mqtt.openf1.org:8084/mqtt`）的实时推送实现。
- [ ] **后端服务化演进**：抽离独立 Spring Boot + MySQL 服务端，将历史赛季数据与用户自定义关注车手持久化入库，提供标准 RESTful 接口。
