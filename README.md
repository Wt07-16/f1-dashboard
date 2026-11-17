# F1 Dashboard

F1 观赛桌面应用:**实时排名 / 积分榜 / 赛历倒计时 / 圈速对比 / 新闻 / 桌面通知**。
纯 Java 代码构建界面(无 FXML)+ CSS 深色主题;中文界面,车手/车队保留英文原名。

## 运行

环境要求:**JDK 26**(或更新)与 Maven。

```bash
# 方式一:终端
mvn javafx:run

# 方式二:IntelliJ IDEA
# 1. File → Open 选择本目录的 pom.xml
# 2. 等待 Maven 依赖下载完成(JavaFX 26.0.2 + Jackson 2.22)
# 3. 运行 f1.app.Main
```

> 本机未独立安装 Maven 时,可用 IntelliJ 自带的那份:
> `/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn javafx:run`

## 页面

| 页面 | 说明 |
|---|---|
| ⚡ 实时排名 | 三种模式:🛰️ **OpenF1 实时**(覆盖练习赛/冲刺排位/冲刺赛/排位赛/正赛全部五种场次;非直播时段自动展示最近场次)/ 🎬 **Mock 演示**(本地模拟比赛,离线可跑)/ 🎞️ **回放**(真实逐圈数据重放已完赛站,0.5–4× 变速) |
| 🏆 积分榜 | 车手/车队榜 + 回溯任意已完赛轮次;**点击行弹出资料卡**(号码/年龄/车队/近 5 站名次轨迹) |
| 🗓️ 赛历 | 全季 23 站,下一场大卡倒计时(走秒),已完赛站可点开正赛成绩 |
| 📈 圈速对比 | 选已完赛站 + 两名车手,每圈用时折线叠加(点图例可单独隐藏/显示一条) |
| 📰 新闻 | F1 官方 + Motorsport RSS 聚合,点击标题用系统浏览器打开原文 |
| ⚙️ 设置 | 数据源切换(真实 / Mock)+ **OpenF1 实时订阅凭据(Sponsor)** + 桌面通知开关 |

## 数据源

- **真实数据**:公开 API [Jolpica](https://api.jolpi.ca)(Ergast 继任者,免费无需 key)—— 积分榜/赛历/逐圈数据;新闻来自官方 RSS。需联网。
- **Mock 演示**:内嵌快照(2026 赛季第 13 站 Monza 赛后真实积分/赛历骨架),完全离线可用;用于开发与演示。
- 切换在「设置」页进行,即时生效并持久化到 `~/.f1-dashboard/config.json`,下次启动沿用。
- **实时页不受数据源开关影响**:演示是本地模拟、回放/近实时始终用真实逐圈数据(切换的只是积分/赛历/新闻)。

- 赛历/积分/新闻均带内存缓存(赛历 5 分钟、积分与新闻 60 秒),重复进入页面即时显示;「刷新」按钮会强制重新联网。启动后会在后台预热常用数据。

## 桌面通知(系统托盘)

- 菜单栏红白方格旗图标。**退出方式**:点窗口叉号 / Cmd+Q / 托盘右键 → 退出 F1 Dashboard —— 三者等效,都直接结束进程(含菜单栏图标),无需强制退出。
- ⏰ **开赛提醒**:下一场正赛进入 90 分钟窗口时弹一次(含本地开赛时间)。
- 🏁 **前三变动**:实时页前三名易手时横幅通知(12 秒节流)。
- 通知开关在「设置」页;平台不支持托盘时自动降级为静默。

## 打包成 macOS 应用(可选)

想要一个能双击启动、Dock 里带 F1 图标、不弹终端窗口的真正 `.app`:

```bash
./packaging/build-app.sh --install
# 产物:target/dist/F1 Dashboard.app,并复制一份到 ~/Applications
```

上面「## 运行」里的 `.command` 启动器仍然可用;`.app` 只是多了图标、少了终端窗口。

> **为什么要 `--install`**:启动台只扫描 `/Applications`、`~/Applications`、`/System/Applications`。
> 留在项目的 `target/dist/` 里是找不到的(而且 `target/` 每次重新打包都会被清空)。

### 换图标

```bash
./packaging/make-icon.sh 我的图.png   # 建议 1024×1024 正方形、带透明通道
./packaging/build-app.sh              # 重新打包
```

不传图片参数时,`make-icon.sh` 会用内置的方格旗设计矢量重绘(与菜单栏托盘图标同一套设计)。
图标按 10 个尺寸逐个矢量重绘而非缩放,小尺寸边缘更干净。

> 打包细节:JavaFX 必须用 `-mac-aarch64` 分类 jar —— 无分类符的 `javafx-*-26.0.2.jar` 是空壳(0 个 class);
> `jpackage` 的 `--app-version` 不允许以 0 开头,故包版本用 `1.0.0`,与 pom 的 `0.1.0` 分开。

## 架构(低耦合)

```
src/main/java/f1/
├── app/       组合根(唯一知道全部实现):Main · F1Application · F1Session(门面) · AppConfig
├── domain/    纯 Java,零依赖:model(record)+ port(接口)+ DataException
├── data/      实现 port:openf1 · jolpica · rss · http · mock —— 互相隔离
└── ui/        只依赖 domain 接口 + F1Session:live · standings · calendar · charts · news · settings · notify · util
```

依赖方向唯一:`ui/app → domain(接口)`,`data → domain(实现接口)`,装配只在组合根。
换数据源/新闻源/通知实现都不动其他层 —— 比如 UI 代码里不出现 HttpClient/JSON/RSS。

## 已知限制

- **直播期间需 OpenF1 Sponsor 凭据**:OpenF1 在比赛场次进行中(开赛前 30 分钟 ~ 结束后 30 分钟)会锁定未认证请求(返回 401)。订阅 Sponsor(€9.90/月)后在「⚙️ 设置」页填入账号密码或 Token,即可解锁练习赛(FP1/2/3)、冲刺排位赛、冲刺赛、排位赛与正赛的全部实时数据。非直播时段无需凭据即可通过「🛰️ OpenF1 实时」查看最近一场场次的真实数据。
- 实时页会在停留期间每 60 秒复查赛历:任何场次(练习/排位/冲刺/正赛)一开跑就自动接入,无需手动刷新。
- 新闻需要联网;断网时页面给出原因与重试引导,不伪造内容。
- 桌面通知依赖系统托盘(菜单栏),无头/受限环境自动静默。
- 数据源为免费公开接口,偶尔可能变慢或被限流 —— 各页面失败均有中文提示与重试入口,刷新即可。

## 技术栈

Java 26 · JavaFX 26.0.2(纯代码 UI + CSS,无 FXML)· Jackson 2.22 · Maven(javafx-maven-plugin)。
