# AGENTS.md

This file provides guidance to Codex (claude.ai/code) when working with code in this repository.

## 项目状态

MiNo 的完整实现已经落地（Rust + Tauri 2.x 后端、vanilla JS 前端）。

**这台开发机没有 Rust 工具链**（`rustc`、`cargo`、`~/.rustup` 都不存在），所以本机跑不了 `cargo check`。编译验证走 CI（[.github/workflows/build.yml](.github/workflows/build.yml)）的 `check` job：`cargo check --all-targets` + `cargo test`。

触发条件有个坑：`push` **只监听 `main` 和 `v*` tag**，往别的分支推不会跑任何东西。要拿反馈就开一个到 `main` 的 PR（`pull_request` 会触发），或者手动 `workflow_dispatch`。出包只看 tag 或手动触发。

要分清「CI 绿过」和「验证过了」。**`cargo check` 不链接、不打包、也从不运行程序。** v0.1.0 已经出包并在实机上跑起来了，第一轮反馈暴露了两个问题（背景图被 `cover` 裁掉、鼠标离开不自动最小化），都已修 —— 但**规格 §27–33 的验收用例还没有被系统性走过**，托盘、全局快捷键、边缘吸附的命中测试都还是未知数。规格 §34 第 6 条：不要因为 CI 绿了就当产品完成。要确认 CI 的实际结果，去仓库的 Actions 页面看，别信文档里的转述。

静态复查能挡住 API 误用（比如修掉的 `TrayIcon::menu()` 并不存在）和并发问题，但**替代不了编译**：trait 约束、泛型实例化、`Send`/`Sync` 之类只有 `cargo check` 说了算。

当前状态：拖动排序、文字颜色、外观设置（PR #1）和 v0.1.2 这一批（活跃窗口门控、每条待办自己的字体、检查更新、标题栏拖动）都已经把 Rust 侧交给 CI 的 `cargo check --all-targets` + `cargo test` 兜底。**这代表它能编译、解析逻辑有回归测试兜着，不代表它在桌面上是好的。**

v0.1.3 这一批（输入框有内容时不自动收缩、拖动期间不收缩、文件日志）同样是**只有 CI 兜编译**，而下面这件事尤其要注意 —— 它**不但是实机才能回答，而且根因至今没被证实**：

- **快速拖标题栏时窗口会从手里消失。** 现在的对策是两道冗余保险（[ui/window.js](ui/window.js) 的 `windowDragging` + `mouseleave` 的 `e.buttons` 判断，见第 12 条），不是一处精确修复 —— 因为「原生移动循环期间页面收不到 mouseup，导致 500ms 收缩定时器在拖动途中到点」只是**最像的解释**，本机跑不了前端也跑不了 Rust，证实不了。**若实机上还复现，先看 `%APPDATA%\com.mino.app\mino.log`**：有 `minimize_to_edge` 而无 `ExitRequested` → 是收缩，看 `reason` 指认的路径；有 `ExitRequested` → 进程真的退了，这一批没覆盖，得另开一轮。

v0.1.2 这一批里，下面几件事**只有实机能回答**，CI 一条都兜不住：

- 悬停门控（切到别的程序后鼠标扫过屏幕边缘是否真的不再展开）
- 失焦收缩的时序，以及**吸附状态下打开托盘菜单会不会误收缩**（Windows 的原生菜单会抢走激活态，这是已知的副作用，需要实机看观感）
- 标题栏拖动 —— Tauri 2 判断拖拽区用的是 `hasAttribute` 还是 `closest`，**在本机无法核实**（没有 Tauri 源码）。现在的改法在两种语义下都成立，但要实机确认「按住 MiNo 那几个字能拖动、5 个按钮点得动」
- `checkUpdate` 的跨域请求在 WebView2 的 `http://tauri.localhost` origin 下能否通过（CSP 已放行 `api.github.com`，CORS 那边 GitHub 返回 `*`，但没跑过就不算数）。被挡的退路是 Rust 侧加 `ureq` 自己发请求

[docs/MiNo_AGENT_SPEC.md](docs/MiNo_AGENT_SPEC.md) 是实现基准和行为契约。注意规格里出现的配置片段、API 名多为示意，一律以当前 Tauri 2.x 实际 schema/API 为准（规格 §34.8 也如此要求）。

## 项目是什么

MiNo 是常驻 Windows 桌面的极简「今日待办」浮窗：全局快捷键唤起 → 输入 → Enter 保存。核心评价标准是**记录一个任务的阻力是否足够低**，而不是功能数量。明确不做：云同步、账号、标签、Markdown、任何 UI 框架。

**网络只有一个例外：设置面板里的「检查更新」**，且只在用户点击时才发一次只读的 GitHub API 请求（[ui/update.js](ui/update.js)）。不做启动自动检查、不做后台轮询、检查结果不落盘 —— 「不主动联网」这条承诺要靠这些约束撑住，加任何自动联网的行为前先想清楚。

## 技术栈

- Rust + Tauri 2.x；Windows WebView2；目标 Win10/11，优先 Win11
- 前端：Vanilla HTML/CSS/JS（ES modules），**禁止** React/Vue/Svelte 及运行时 npm UI 组件库
- 持久化：单个本地 JSON，无网络依赖（唯一的联网点是用户手动触发的「检查更新」，见上文）
- 无打包器：`build.frontendDist` 直接指向 `ui/`，前端靠 `withGlobalTauri` 拿 API

## 命令

```bash
npm run dev          # = tauri dev
npm run build        # = tauri build（NSIS 安装包）
npm run icons        # 重新生成 src-tauri/icons（Node 脚本，零依赖）

cd src-tauri
cargo check          # Rust 侧改动的首选反馈
cargo test
```

**上面这几条在开发机上跑不了**（没有 Rust 工具链）。Rust 侧的反馈走 CI：[.github/workflows/build.yml](.github/workflows/build.yml) 的 `check` job 跑 `cargo check --all-targets` + `cargo test`，`build` job 出 NSIS 安装包并上传为 artifact / 发布 Release。**`check` 只在推 `main`、推 `v*` tag 和 PR 上触发** —— 往别的分支推不会跑任何东西。没在 CI 上跑绿过的改动，一律当作没验证过。

没有前端构建步骤，改 `ui/` 下的文件直接生效。`tauri.conf.json` 里没有 `beforeDevCommand`。

**但这意味着前端完全没有编译期保护**：`tauri build` 只是把 `ui/` 原样嵌进二进制，不做解析。一个 JS 语法错误会静默地进安装包，表现成白屏。所以 CI 的 `check` job 里补了一步前端静态检查（`node --check ui/*.js` + `tools/check-commands.mjs` + `tools/check-version.mjs`）。这三条**在本机也能跑**（Node 可用），改完前端先本地过一遍再推。

`check-commands.mjs` 除了比对 command，还会比对**事件名**：Rust `emit` 的每个事件前端都得真的 `listen` 着。对不上是静默失败（托盘点「设置」没反应），`cargo check` 和 `node --check` 都看不见 —— 两边各自都合法，只是对不上。批量改名时最容易漏掉其中一边。

`package.json` 里的 `"type": "module"` 是为了让 node 按 ESM 解析 `ui/*.js` —— 上面那几条依赖它。

图标由 [tools/gen-icons.mjs](tools/gen-icons.mjs) 用 Node（zlib 手写 PNG + ICO 容器）生成。`tauri-build` 会把 `icon.ico` 编进 Windows 资源段，缺了它 `cargo build` 直接失败，所以这个脚本是仓库自举的一部分。

## 架构

**Rust 是数据的唯一权威来源。** 前端不推演状态变化：每个会改数据的 command 都返回完整的 `AppData` 快照，前端整体重绘（[ui/state.js](ui/state.js) 的 `call()` 自动把结果写回 `state.data`）。这样跨日归档这类逻辑不会出现「前端算的日期」和「落盘的日期」各说各话。修改数据请沿用这个模式，不要让前端自己维护一份可变状态。

**所有数据变更都经由 [commands.rs](src-tauri/src/commands.rs) 的 `current` / `mutate` 两个入口**，它们持有 `DATA_LOCK`，把「读 → 改 → 写」串成一个临界区。command 全标了 `#[tauri::command(async)]`，真正跑在多线程运行时上，而落盘写的是整份 `AppData` —— 不加锁时两次并发的 invoke 会各自从同一份快照出发、后者整份覆盖前者（前端确实有不 await 就连发的调用）。新增 command 请走这两个入口，不要自己 `state::load` + `state::save`。

临界区里含 `fsync`，**不要从主线程调用 `mutate`**。窗口拖动那条落盘路径（`window::save_position_throttled`）是把活儿丢到后台线程的 —— 它跑在 `WindowEvent::Moved` 回调里，`#[tauri::command(async)]` 只改变 IPC 派发方式，直接调仍是在主线程上同步做全套文件 IO。

前端职责边界（规格 §18）：Rust 管系统能力（快捷键、托盘、窗口、文件读写、字体枚举），JS 管 UI 与渲染。

### Rust 模块

| 文件 | 职责 |
|---|---|
| [main.rs](src-tauri/src/main.rs) | 启动顺序、插件注册、`generate_handler!`、窗口事件分发 |
| [state.rs](src-tauri/src/state.rs) | 数据模型、原子持久化、损坏容错、跨日迁移、排序 |
| [commands.rs](src-tauri/src/commands.rs) | 全部 command；背景与运行时信息也在这里 |
| [window.rs](src-tauri/src/window.rs) | 位置恢复、多显示器兜底、置顶、边缘吸附、前台全屏探测 |
| [tray.rs](src-tauri/src/tray.rs) | 系统托盘（唯一的退出入口） |
| [shortcut.rs](src-tauri/src/shortcut.rs) | 全局快捷键，含冲突回退 |
| [autostart.rs](src-tauri/src/autostart.rs) | 开机自启动（注册表 Run 项 + 任务管理器的禁用标记、启动自愈） |
| [reminder.rs](src-tauri/src/reminder.rs) | 提醒调度（轮询到点判定 + 独立的置顶浮窗） |
| [fonts.rs](src-tauri/src/fonts.rs) | 字体枚举、导入、字节读取 |

规格 §25 里还有个 `background.rs`，实际实现把背景逻辑放进了 `commands.rs`，没有单独成文件。

### 改数据模型之前必读

**给 `Todo` / `Settings` 加字段时，少一个 `#[serde(default)]` 就能清空用户的全部待办。** 这是这个仓库最容易犯、后果最重的错误，而且编译期毫无征兆。

三个必须记住的点：

1. **容器级和字段级的 `default` 语义不同，不能混用。** 字段级 `#[serde(default)]` 取的是**该字段类型**的 `Default`（`String` 就是空串），容器级才是取 `impl Default for 该结构体` 里的值。`Settings.text_color` 故意只靠容器级，就是为了拿到 `DEFAULT_TEXT_COLOR` 而不是空串。
2. **`AppData` 上的 `default` 不会保护 `Settings` 内部的字段。** 它只在 `settings` 整个键缺失时才生效。现存用户的 `data.json` 个个都有 `settings`、个个都没有新字段 —— 这正是出错的那条路径。
3. **`load()` 解析失败的处理是 `backup_broken()` + 返回默认数据**（[state.rs](src-tauri/src/state.rs) 的 `load`）。文件被改名成 `.broken`，用户看到的是所有待办凭空消失。**任何解析失败都表现为「用户的活儿没了」**，所以这类改动必须配单元测试 —— `state.rs` 的 `legacy_settings_without_text_color_still_parses` 就是这个用途的回归测试。

另外，**新增字段的默认值必须让老数据的行为保持不变**。`Todo.order` 默认 0 是个恒等键，排序仍落回 `created_at` 倒序，与加字段前一模一样；如果给它一个非 0 的默认值，所有老任务的相对顺序都会被打乱。

数据版本变迁写在 `migrate()` 里。覆盖老用户的值之前，先确认那个值不可能是用户的真实选择（v1→v2 重置 `mask_opacity` 之所以安全，是因为前端从来没读过这个字段，磁盘上的 0.65 只是初始默认值）。

**还有一个比 serde 更隐蔽的坑：默认值选得不对，等于没迁移。** `Todo.font` 的默认值是空串，如果前端把空串解释成「跟随当前的 `settings.fontFamily`」，那么用户每改一次全局字体，所有老待办还是会跟着变 —— 功能的意图（只影响新任务）就落空了。所以 v2→v3 的迁移必须把老待办**当时正在用的字体写死**到每条上（`stamp_missing_todo_fonts`），空串只作为「手改过文件」的兜底。

选默认值时要问的不是「解析会不会失败」，而是「这个值会不会让老数据的行为在**未来**发生变化」。`Todo.order` 默认 0 之所以安全，是因为它对 `sort_todos` 是恒等元，而且永远恒等。

### 改名迁移（v0.2.0，MiniMemo → MiNo）

改名不只是改字符串。有两处**会写盘**的东西跟着改了，各自配了一次迁移；两处都刻意做成**幂等 + 绝不删用户数据**。

1. **`identifier` 从 `com.minimemo.app` 改成 `com.mino.app`，数据目录跟着变。** `state::adopt_legacy_data_dir` 把老目录整体接管过来（待办、背景图、导入字体、日志都在里面）。

   ⚠️ **它必须在 `logging::init()` 之前调用。** 日志文件也住在这个目录里：先搬完再开日志，老日志一起过来；顺序反了就会先在**新**目录建出一个空日志，老日志留在原地，而搬迁会因为「新目录已存在」直接放弃 —— 用户的待办就真没了。

   由此还有一层：正因为它跑在 logger 装好之前，它**不自己打日志**，而是返回 `AdoptOutcome`，由 [main.rs](src-tauri/src/main.rs) 在 `logging::init()` 之后调 `report()`。在那边直接 `info!`/`warn!` 会被整个丢掉 —— 连「接管失败、数据还在老目录，可手动移动到 X」那句也一样，而那正是搬迁失败时用户**唯一**的救援线索。这个坑是 2026-09-13 第一次实机跑搬迁时发现的：搬迁确实成功了，但日志里只有 `MiNo 0.2.0 启动`，没有 `数据目录已接管`（README 向用户承诺过这一行）。**凡是「必须在 logger 就绪前跑」的代码，都要用返回值把结论带出来再记。**

   还有三点：它**刻意不用 `state::data_dir()`**（那个会 `create_dir_all`，新目录一建出来，Windows 上 `rename` 就必然失败）；判断「已经搬过了」用的是 `新目录里有 data.json` 而不是「新目录存在」，所以半截复制下次启动会自动补完（为此 `copy_tree` 把 `data.json` 排在最后复制）；**老目录永远不删**，最差情况是用户看到初始状态、数据原封不动躺在老地方、日志里写着两个路径。

2. **导入字体的 family 前缀 `MiniMemo ` → `MiNo `**，由 `state::rename_font_family_prefix` 改写。这个前缀写在磁盘上**三处**，漏一处的表现都是「我导入的字体自己变了」（指向一个永远注册不进来的 family，浏览器静默回退到 `sans-serif`，用户怎么改设置都救不回来）：
   - `settings.font_family`
   - `settings.custom_fonts[*].family` —— **最容易漏**，它不参与正文渲染，坏了只有字体列表看起来是坏的
   - `todos[*].font` 与 `archives[*].font`

   `FONT_FAMILY_PREFIX` 因此是常量而不是字面量，且与 [ui/fonts.js](ui/fonts.js) 的 `familyOf()` **必须逐字一致**。再改这个前缀就得再配一次迁移。

**⚠️ 目录搬迁没有单元测试覆盖**（它要一个真的 `AppHandle`），`cargo check` 只证明它编译得过。改它必须实机走一遍「装老版 → 产生数据 → 装新版」。字体前缀那条有回归测试兜着（`state.rs` 的 `migrate_rewrites_legacy_font_family_prefix_everywhere`）。

### 每条待办自己的字体

`Todo` 上有 `font`（完整 CSS 链）和 `font_id`（导入字体 id，系统字体时是 `None`）两个字段，由 `add_todo` 在创建时快照当前设置。三个要点：

- **`font_id` 必须单独存**，不能只留 `font` 链。导入字体的字节不跨进程缓存，重启后要靠这个 id 重新 `ensureFont`，否则那条任务会静默回退成默认字体。
- **前端渲染用 inline `style.setProperty('font-family', ...)`**，不走 `--note-font` 变量 —— 变量是全局的，表达不了「每条不一样」。写之前用 `fonts.isLoaded(todo.fontId)` 判断导入字体是否已注册：没注册就改用全局字体，结果确定、可预期（否则浏览器会静默回退到链尾的 `sans-serif`，表现成「字体自己变了」）。
- **引用导入字体的任务在字体被删掉时要一起清干净**，见 `commands::remove_font` 和 `fonts::prune_missing` —— 这两个地方最容易漏，漏了的表现就是上面那条「字体自己变了」。

`apply_font_to_all`（设置面板的「应用到全部」）是把当前字体**写入**每条任务，不是清空。清空看着更省事，但那样用户之后再改一次字体，这些任务又会跟着变，与该按钮的用途正好相反。

## 几个关键机制

这几处需要同时理解数据层、窗口层和 UI 层：

1. **跨日迁移**（[state.rs](src-tauri/src/state.rs) 的 `rollover`）。应用可能休眠或隐藏数天，所以**不能依赖 `setTimeout` 跨越零点**，只比对本地日期与 `settings.lastCheckDate`。已完成项进 `archives`，未完成项保留并显示日期前缀。首次启动（`lastCheckDate` 为空）只设日期、不归档 —— 否则会把用户的全部历史误判成「昨天的已完成」。`archives` 有 500 条上限，超出丢最旧。

   ⚠️ **日期前缀的「今天」必须在客户端算**（[ui/todos.js](ui/todos.js) 的 `todayKey`）。曾经它读的是 `settings.lastCheckDate`，注释里写着「跨日检查跑完之后它就等于今天」—— 那个推理只在**启动时**成立：`load_state` 只在 boot 里调一次，之后 `state.data` 要靠别的 command 返回快照才刷新。窗口跨夜开着时这个值停在前一天，于是「昨天建的」和「今天建的」判成同一天，前缀整个不出现。2026-09-14 实机复现（日志显示应用从 09-13 16:04 起没重启过）。**失败形态特别迷惑**：更早的任务仍带前缀，只有最近一天的不带，看着像偶发。凡是要「当前时间」的地方都照此办理 —— 别问服务端要一个顺带更新的字段。

   ⚠️ **`rollover` 那次日期更新经常没被落盘。** [main.rs](src-tauri/src/main.rs) 和 [commands.rs](src-tauri/src/commands.rs) 的 `current` 都是 `if rollover(...) > 0 { save }`，而返回值是**归档条数**：当天没有完成任务时 `archived == 0`，`last_check_date` 只更新在内存里。只有 `mutate` 会无条件保存。这不影响正确性（重跑一次是幂等的），但意味着**磁盘上的 `lastCheckDate` 本来就滞后** —— 任何拿它当「今天」用的代码都会漂。上面那条前缀的坑就是这么来的。

   ⚠️ **同一个原因还有一个没修的表现**：跨夜开着时，昨天已完成的任务**不会立刻进归档**，要等下一次数据变更触发 `mutate` 才结算。用户看不到「移入归档」这一步，但列表里的已完成项也不会消失得莫名其妙 —— 暂时按现状保留，要修就得让窗口每次显示时重新拉一次 `load_state`（影响面比前缀那条大得多）。

2. **原子写入与损坏容错**（`state.rs` 的 `save` / `load`）。临时文件 → flush → sync → rename；Rust 的 `fs::rename` 在 Windows 上走 `MoveFileEx(MOVEFILE_REPLACE_EXISTING)`，覆盖已存在文件也是原子的。解析失败时备份成 `data.json.<时间戳>.broken` 再用默认数据启动。**任何数据异常都不得阻止应用启动。**

3. **边缘吸附**（[window.rs](src-tauri/src/window.rs)）。两个坑：**单位**（显示器信息是物理像素，窗口尺寸按逻辑像素写，混用会在 125% 缩放的屏幕上偏差 25%，所以统一先换算成物理像素）和**自触发**（吸附时自己调 `set_position`/`set_size` 会再次触发 `Moved`，进而重新判定吸附 —— `PROGRAMMATIC` 标志用来标记程序发起的移动，必须覆盖每一条改位置的路径，否则窗口会抖动）。吸附开关与阈值缓存在 `SNAP_CFG` 里，因为 `Moved` 每秒来几十次，不能每次都读盘解析 JSON。解除吸附（`release_snap`）还有第三个坑：恢复宽度必须以**当前贴边的那条边**为锚，而不是沿用 `pos.x`。右侧收缩成感应条后 `pos.x` 已经贴着屏幕右缘，沿用它会得到一个几乎全在屏幕外的窗口（280px 宽只剩十来像素可见）；左侧则恰好相反。但同一个函数也被「用户把窗口拖离边缘」触发，所以只有确实还贴着边时才允许移动位置，否则会在拖动中途把窗口拽回去。

4. **字体系统**（[fonts.rs](src-tauri/src/fonts.rs) + [ui/fonts.js](ui/fonts.js)）。系统字体走 `font-kit`（Windows 上即 DirectWrite，与 WebView2 解析 CSS `font-family` 用的是同一套库）；导入字体走「复制进 app data → 读字节 → `new FontFace(family, ArrayBuffer)`」。**不要改成 `@font-face { src: url(...) }`** —— 那要么依赖 `file://`（被 WebView2 的 origin 隔离挡掉），要么需要开启 asset protocol 并放宽 CSP，而且字体请求是 CORS 模式的，跨 origin 能否通过并不确定。当前方案不发起任何请求，因此**字体这块**不需要任何安全配置变更（CSP 里唯一的出站例外是「检查更新」用的 `https://api.github.com`，见第 10 条）。

5. **首屏字体引导**（[ui/boot.js](ui/boot.js)）。设置存在 Rust 侧、只能异步取，等它回来时页面早画完了，会闪一下默认字体。`boot.js` 在 `<head>` 里同步读 localStorage 缓存并写 CSS 变量。它**必须是阻塞式普通脚本**（不能是 `module` / `defer`），CSP 是 `script-src 'self'` 所以也不能写成内联脚本。

6. **拖动排序**（[ui/dnd.js](ui/dnd.js) + [state.rs](src-tauri/src/state.rs) 的 `Todo.order`）。三个坑：

   - **不能在 `pointerdown` 里就 `setPointerCapture`。** 一旦捕获，`click` 会被重定向到 `li`，用户在勾选框上按下再轻微抖动，勾选就失效了。只有位移越过阈值、确认意图是拖动之后才接管指针。
   - **`renderList()` 的 `replaceChildren()` 会在拖动中途把正在拖的 `li` 从 DOM 摘掉**，元素一移除 pointer capture 就隐式释放，`pointerup` 再也收不到，`reorder_todos` 永远不会被调用。所以 [ui/app.js](ui/app.js) 的 `render()` 在拖动中要整轮跳过，`renderList()` 开头再加 `cancelDrag()` 兜底。
   - **几何算的是未完成项**。未完成项由 `sort_todos` 保证连续排在前面，拖动也只在这段内换位 —— 已完成项永远沉底，跨不过那条边界。位移折算依赖「每行严格等高」（`.todo` 无 margin、`.list` 无 gap、`.todo-text` 强制 `nowrap`），给其中任何一个加 margin / gap / line-height 都会让换位算错。

   `reorder_todos` 只收**未完成**的 id，未列出的接末尾并保持原相对次序 —— 这样并发新增或归档过的残缺列表只会降级，不会报错、不会交错。赋值结果必须是 `sort_todos` 的不动点，否则 `mutate` 结尾那次排序会把结果打乱。

7. **背景可读性与外观变量**（[ui/appearance.js](ui/appearance.js) + [styles.css](ui/styles.css)）。原来自定义图片是糊的，根因是 `#app` 上的 `backdrop-filter` 在模糊**整个窗口背后**的东西。现在图片模式强制关掉它，改由文字投影 + 颜色选择撑对比度。

   三条必须记住的约束：

   - **用户的文字颜色只写 `--note-*`，绝不碰 `--text` / `--text-dim` / `--text-faint`。** 后者是窗口 chrome（标题栏、设置面板、toast、字体列表）的固定配色 —— 把它们一起改掉，用户选了深色之后设置面板会变成深底深字，**再也改不回来**。
   - **遮罩浓度只能有一层来源。** 有图片时 `#app` 的 background 必须是 `transparent`、`backdrop-filter` 必须是 `none`，否则会和 `.bg-mask` 叠成 `0.25 + 0.75×α`，滑块的读数对不上眼睛看到的东西。同理，无图片时 `#app` 用的是另一个变量 `--app-mask`（固定 0.65），不是 `--mask`（图片遮罩，滑块驱动）—— 混用会让「调图片遮罩」意外改掉默认的亚克力观感。
   - **背景必须是三层独立元素**（`.bg-blur` / `.bg-image` / `.bg-mask`），不能图省事写成 `#bg` 自己的 `background` 加伪元素。`filter` 会作用于**整个子树**，把 `blur()` 写在 `#bg` 上会把上面那层清晰图一起糊掉 —— 那正是最初要修的问题。同理，`background-size` 在清晰层必须是 `contain` 而不是 `cover`：窗口是 280×380 的竖长条，`cover` 对横屏图等于只留中间一条。图片 URL 通过 `--bg-image` 变量同时喂给模糊层和清晰层，见 [ui/background.js](ui/background.js)。

   投影方向随文字颜色的亮度自动反转（亮字配暗影、暗字配亮光晕）。少了这一步，用户在暗背景图上选深色正文会比默认白字还难读 —— 颜色选择就从解决问题的工具变成了制造问题的工具。两个滑块互斥：有图片时只显示「背景遮罩」（模糊被 CSS 强制关掉），无图片时只显示「背景模糊」（没有图片可遮）。

8. **鼠标离开自动最小化**（[ui/window.js](ui/window.js) + [commands.rs](src-tauri/src/commands.rs) 的 `minimize_to_edge`）。定时器到点后调的是 `minimize_to_edge`，**不是** `set_edge_collapsed(true)` —— 后者在窗口没吸附时会直接 `return Ok(())`（[window.rs](src-tauri/src/window.rs) 里那句「没吸附就不该有收缩行为」），于是「离开就最小化」实际只在贴着屏幕边缘时成立，窗口停在屏幕中间时完全没反应。`minimize_to_edge` 自己分流：吸附了收缩成感应条，没吸附就隐藏窗口，和标题栏那个 `—` 按钮是同一个行为。

   到点后**先过两道抑制闸**（输入框非空、正在拖窗口，见第 9、12 条），没过就重新排程。重排程而不是 `return` 是必须的：这条 timer 链是自动收缩唯一的触发源，直接返回等于把它掐断。

9. **悬停门控与活跃状态**（[main.rs](src-tauri/src/main.rs) 的 `WindowEvent::Focused` + [ui/window.js](ui/window.js) 的 `setActive` / `refreshHoverBlock`）。两件事，判据不同，别混在一起：

   - **悬停展开的门控条件是「前台有没有全屏程序」**，不是「MiNo 是不是活动窗口」。
   - **活跃状态管的是「切走就收缩、切回来就展开」**。

   ⚠️ **早先用的是「MiNo 必须是活动窗口」当悬停门控，那是个自锁的判据**：感应条缩在屏幕边上的时候，MiNo 恰恰不可能是活动窗口，于是 `mouseenter` 永远进不去 `expand`，感应条自己醒不过来 —— 每次都得先按 `Alt + Space`。用户报的「鼠标碰上去没反应」就是它。真正会被浮窗打扰的场景只有全屏内容（全屏视频、全屏演示、F11 网页），而那用 `foreground_is_fullscreen` 单独问就行。

   必须记住的点：

   - **全屏判据在 Rust 侧**（[window.rs](src-tauri/src/window.rs) 的 `foreground_is_fullscreen`，走 `windows-sys` 的 `GetForegroundWindow` / `GetWindowRect` / `GetMonitorInfoW`）。浏览器里看不到别的窗口，这件事前端做不到。规则是「前台窗口的矩形盖住了它所在显示器的矩形」，两处细节见该函数的注释：**必须用 `GetShellWindow` 排掉桌面**（桌面也铺满整块屏幕，不排掉的话「回到桌面后没反应」会原样保留），**必须留容差**（无边框全屏的边界常差一两个像素）。
   - **查不到一律放行。** `foreground_is_fullscreen` 的每条失败路径都 `return false`，前端 `refreshHoverBlock` 的 `catch` 也把值置回 `false`、变量初值同样是 `false`。这个值只用来*抑制*悬停，失败方向必须偏向「照常展开」：退化成多展开一次，远好过退化成感应条永远叫不醒。
   - **活跃状态只有 Rust 的 `WindowEvent::Focused` 一个来源**，不用 DOM 的 `focus`/`blur`：`document.hasFocus()` 受 webview 内部焦点（点到输入框、点到按钮）影响，和「这个窗口是不是系统当前活动窗口」不是一回事。那个回调**跑在主线程**，所以只能 `emit`，不能碰 `state::load` / `set_edge_collapsed`。
   - **`mouseenter` 里那次查询是异步的**（`refreshHoverBlock().then(...)`）。多出来的几毫秒对 4px 感应条的手感没有影响，但**别把它改成同步等待**，也别在 `mouseleave` 里再查一次 —— 那边复用 `mouseenter` 查到的值就够了。
   - **`mouseleave` 也要门控**，不能只门控 `mouseenter`。窗口**没吸附**时鼠标划过窗口再离开，会走到 `minimize_to_edge` 的「没吸附」分支把窗口整个隐藏掉 —— 那是同一类骚扰。它开头还有一道 `if (e.buttons !== 0) return;`：鼠标键还按着就不是「离开」，而是在拖东西（拖窗口或拖待办），不该武装收缩定时器。
   - **`active` 的初值是 `true`**。窗口本来就是 `show()` + `set_focus()` 出来的，而且 Rust 那次 `Focused(true)` 可能早于前端注册监听。副作用是：万一这个事件通道整个失效，`active` 停在 `true`，行为退化成改动之前的样子，不会把功能弄坏。
   - **「立刻收缩」用 `set_edge_collapsed(true)`，不是 `minimize_to_edge`。** 前者在未吸附时是 no-op，语义正好是「若吸附则收缩」；后者会把没吸附的窗口整个藏起来 —— 每次 Alt+Tab 都藏窗口是灾难。

   **自动收缩有两道抑制闸，两条收缩路径（`mouseleave` 定时器 + 失焦）都要过。**

   - **输入框那点事还没完（`holdWindowOpen`）就不收缩。** 它由**两条**组成，**缺一不可**：

     - **有没提交的内容**（`hasPendingInput`，`input.value.trim()` 非空）—— 一直挡到用户自己清空或提交。用户敲了一半切去别处抄点东西再回来时，窗口不该带着他的字一起消失。
     - **刚刚在输入框里动过手**（`INPUT_GRACE_MS` = 2s，由 `keydown` / `input` / `compositionstart` 刷新）—— 这条是**回车提交那一下的余地**，看着像冗余，其实不是冗余。

     ⚠️ **只留第一条会复现「按回车窗口立刻被收走」。** 回车提交走的是 `input.value = ''`（[ui/todos.js](ui/todos.js)），而**程序化赋值不触发 `input` 事件** —— 那一刻内容判据会瞬间翻成「没有内容」。偏偏收缩定时器一旦被 `mouseleave` 武装过，就会每 500ms 无限重排程轮询下去（见第 8 条），所以「清空」到「收缩」之间只隔几十毫秒。0.1.2 里回车是 `keydown`、会刷新 `lastTypingAt`，白送 2 秒保护；那个判据被整个删掉时就露出了这个洞。`keydown`（而不是只监听 `input`）是这条判据的关键 —— 只有它能捕捉到回车。

     不能用 `document.activeElement === #input` 代替：输入框在启动时和每次添加后都会被 `focusInput()` 聚焦，那个判断几乎恒为真，会让自动收缩永久失效。也刻意**没有**引入「输入法组合中」之类的布尔标志 —— `compositionend` 万一不触发就是自动收缩永久失效且悄无声息，而这个仓库最忌讳的正是这种失败形态。
   - **用户正按着鼠标拖窗口（`windowDragging`）就不收缩。** 见第 12 条。

   两道闸都只拦**自动**路径；用户主动动作（`✕` / `Esc` / `—` 按钮）照常生效。

   窗口操作（`set_edge_collapsed` / `minimize_to_edge`）在 [ui/window.js](ui/window.js) 里串成一条 FIFO 链再 invoke。它们都是 `#[tauri::command(async)]`、跑在线程池上，**两次 invoke 的执行顺序没有保证**；快速 Alt+Tab 时收缩和展开几乎同时发出，乱序落地就会停在错误状态。

10. **检查更新**（[ui/update.js](ui/update.js) + [ui/version.js](ui/version.js) + [commands.rs](src-tauri/src/commands.rs) 的 `open_release_page`）。不用 Tauri 官方的 updater 插件：那需要一对 minisign 密钥、CI 里的签名密钥、一份 `latest.json`，为了「看看有没有新版」不值当。现在是前端 `fetch` GitHub 的公开 API + 手动比对版本号。

    四个坑：

    - **仓库必须是公开的。** 匿名请求私有仓库的 API 一律返回 404（GitHub 连"这个仓库存在"都不泄露），而应用里没有 token、也不可能有 —— 桌面应用里塞 token 等于把 token 公开。这个坑是 2026-09-10 出 v0.1.2 时才发现的：当时仓库是私有的，`/releases/latest` 返回 404，而下面那段代码把 404 解释成「GitHub 上还没有已发布的版本」（[ui/update.js](ui/update.js) 的 `fetchLatestTag`），于是用户看到的是一句**错误**的提示，而不是"检查更新坏了"。已认证请求返回 200、匿名返回 404，两者对照才能确认是这个原因。**若将来把仓库改回私有，这个功能会重新静默失效成那句错误提示。**
    - **不要给那个 `fetch` 加任何自定义头**（尤其 `X-GitHub-Api-Version`）。`GET` + 无自定义头才是 CORS 的「简单请求」，不触发 `OPTIONS` 预检；加了不在 safelist 里的头就会先发一次预检，而那次能不能过不在我们控制范围内。
    - **CSP 的 `connect-src` 里必须有 `https://api.github.com`**，否则请求在 CSP 层就被拦（早于 CORS，报错信息还很难懂）。这是全项目唯一的出站通道，精确到 host，不要写成通配符。
    - **打开 Release 页面要走 Rust 的 `open_release_page`**，不能用 `<a target="_blank">` —— Tauri 的 webview 不会把它交给系统浏览器，要么没反应，要么直接在 webview 里导航、把整个界面顶掉。该 command 里的 tag 来自 GitHub API，是**不可信输入**，会拼进 `cmd /C start` 的命令行，所以字符集必须卡死在 `[A-Za-z0-9._-]`（`& ^ | < > "` 空格在这里就是命令注入）。

    版本比较单独放在 [ui/version.js](ui/version.js)：零依赖的纯函数，可以被 [tools/check-version.mjs](tools/check-version.mjs) 在本机直接测 —— 它是整个功能里唯一不需要联网、也不需要实机就能验证的部分。**当前版本号只有 `CARGO_PKG_VERSION` 一个来源**（`runtime_info().version`），前端不存第二份；发版时记得 `Cargo.toml` 和 `tauri.conf.json` 的 `version` 要一起改。

11. **标题栏拖动**（[ui/index.html](ui/index.html)）。`data-tauri-drag-region` 要挂在**真正被点到的那个元素**上，光挂在 `<header class="titlebar">` 上不够：Tauri 判断的是事件目标自身带不带这个属性，而 `.title` 是 `flex: 1`、盖住了标题栏中段，于是能拖的只剩左右两端几个像素的 padding。

    所以 `.titlebar` / `.grip` / `.title` 各带一份，但 **`.actions` 里的按钮一个都不能加** —— 它们有自己的点击语义，变成拖拽区就点不动了。同理，设置面板是 `position: fixed; inset: 0; z-index: 20`，打开时整条标题栏都被盖住，所以 **`.settings-head` 和它里面那个 `span` 也要带属性**（面板**主体**绝不能加：那里面有搜索框、滑块和可滚动区域）。

12. **拖动窗口期间不自动收缩**（[ui/window.js](ui/window.js) 的 `windowDragging`）。这是「快速拖标题栏时窗口会从手里消失」那个 bug 的对策 —— 注意**根因至今未被证实**，只是最像的解释（见下），所以这里是**两道冗余保险**而不是一处精确修复。

    - **`data-tauri-drag-region` 由 Tauri 注入的脚本处理，JS 侧一个监听器都没有。** 我们只在 `mousedown` 里用 `closest('[data-tauri-drag-region]')` 判断「拖动开始了」（用 `closest` 而不是看目标自身：`.title` 盖住了 `.titlebar` 中段）。
    - **`mouseup` 在拖动时根本不会到达页面。** 拖窗口走的是 Windows 原生模态移动循环，期间页面收不到任何鼠标事件 —— 所以清位不能只等 `mouseup`，现在挂了 `mouseup` / `pointerup` / `pointercancel` / 「`mousemove` 且 `e.buttons === 0`」/ 窗口 `blur` / **20 秒兜底超时**。最后那个不是多余的：松手时指针若在窗口外，就没有任何一路会到。
    - **`mouseleave` 开头那道 `e.buttons !== 0` 是独立于状态机的第二道网**（见第 9 条）。它只做抑制，即使 WebView2 下 `e.buttons` 不可靠也只是退回没有它的行为。

    最像的解释：拖动开始时那次 `mouseleave` 武装了 500ms 收缩定时器，而原生移动循环期间收不到事件，定时器照常在拖动途中到点 —— 吸附状态下 `minimize_to_edge` 走的是**收缩分支而不是隐藏**（第 8 条），所以看起来像「程序直接关闭」，其实只是缩成了 4px 感应条。

13. **文件日志**（[logging.rs](src-tauri/src/logging.rs)）。存在的理由只有一个：窗口收缩那类 bug 在实机上偶发、本地复现不了，事后没有现场就只能猜。**release 构建是 `windows_subsystem = "windows"`，而 `env_logger` 只写 stderr —— 装出来的包里一行日志都没有**，所以它已被移除，换成手写的 `log::Log` 写 `%APPDATA%\com.mino.app\mino.log`（零新依赖，`log` facade 本来就在）。超过 1MB 在启动时轮转成 `.log.old`；任何 IO 失败都静默吞掉 —— release 是 `panic = "abort"`，日志不能自己成为死因。

    **`RunEvent::ExitRequested` / `Exit` 那两行是判决书**：窗口消失了，到底是有没有这两行，区分「缩成了感应条」和「进程真的退了」。`minimize_to_edge` / `set_edge_collapsed` / `hide_window` 都带一个 `reason: Option<String>`，前端用 `why(tag)` 把当时的判据状态（`active` / `pending` / `drag`）拼进去 —— 这样日志能指认是**哪条路径**触发的，而不是又一轮猜。reason 是我们自己的常量，Rust 侧仍按不可信输入处理（截断 + 剔控制字符），且**只记录、不参与任何分支**。

    ⚠️ **`Option<T>` 参数在 Tauri 里仍然必须显式传这个键** —— 键整个缺失是 `missing_arg` 错误，不是 `None`，只有显式传 `null` 才是 `None`。`set_font` 的 `id` 就是这么调的（`option.id ?? null`）。

    ⚠️ **`log` 必须是 `log = { version = "0.4", features = ["std"] }`，`features = ["std"]` 不能省。** log 0.4 的 `[features]` 里**没有 `default`**，裸写 `log = "0.4"` 等于 `std` 关闭，`set_boxed_logger` 会被 `cfg` 掉（它门控在 `alloc` 上，而 `std = ["alloc"]`）。以前这里不需要显式开，是因为 `env_logger` 依赖 `log/std`、Cargo 的 **feature 合并**顺带开了 —— 删掉 `env_logger` 的那一刻就没人开它了。这是「删一个依赖会悄悄改掉另一个依赖的 feature 集」的实例，CI 抓到的（E0425），本地没有 Rust 工具链就发现不了。

    **日志里绝不出现待办正文**：它在用户目录里，不该多存一份内容。同理，往 `on_window_event` 里加日志前先想清楚频率 —— `Focused` 是人手动触发的（一次 Alt+Tab 一条），可以记；`Moved` 每秒几十次，只在吸附跃迁时记（`handle_moved` 里那两条 `info!`）。

14. **二次编辑待办**（[ui/todos.js](ui/todos.js) 的 `startEdit` / `commitEdit` + [commands.rs](src-tauri/src/commands.rs) 的 `edit_todo`）。双击任务文字就地换成 `<input>`，回车或失焦保存，`Esc` 取消，清空视同取消。Rust 侧是新 command `edit_todo`，只碰 `text` 一个字段 —— 数据函数抽成了 `state::set_todo_text` 纯函数，就是为了能在 `mod tests` 里**逐字段**断言这件事。

    四个非显然的约束，都是踩过或推演过的：

    - ⚠️ **「编辑中不重绘」的守卫必须写在 `renderList()` 里，不能写成 `app.js` 的 `render()` 顶部一个 `if (isEditing()) return`。** 后者会连带冻住 `renderWindowUI()` 和 `applyAppearance()` —— 编辑期间点 📌 没反应、点「应用到全部任务」弹了提示却一条都没变。`renderList()` 里也只跳过 `replaceChildren` 那一段，底部的进度和「清空已完成」的禁用态永远要更新。同理，`isDragging()` 那条守卫仍在 `render()` 里，**别去动它**（它工作时带一段价值很高的注释）。
    - ⚠️ **`.todo-edit` 的 `height: calc(--note-size * --note-lh)` 是行高契约的一部分**（[styles.css](ui/styles.css)）。行高必须是 28.125px：`.todo` 的 padding 5px×2 + 18.125px 行盒。它一变，[dnd.js](ui/dnd.js) 的 `Math.round(dy / rowH)` 换位就会算错。改字号/行高时 `--note-size` / `--note-lh` 是唯一来源，`.todo-text` 和 `.todo-edit` 都从它算。
    - ⚠️ **`<input>` 不继承 `color` / `text-shadow` / `text-decoration`。** UA 样式表给的是 `color: fieldtext` / `background-color: field`，都是元素级声明，作者侧「继承来的值」输给它 —— 不显式写的话，用户配了背景图 + 浅色正文时编辑框里的字直接看不见。已完成的删除线同理，要单独补一条。
    - **`Esc` 必须 `stopPropagation()`。** [ui/window.js](ui/window.js) 那个全局 Esc 挂在 `document` 上、**不看 `e.target` 也不看 `e.defaultPrevented`**，放它冒上去窗口就没了。反过来，设置面板开着时要主动放行（`if (state.ui.settingsOpen) return`），否则面板关不掉。
    - **IME 守卫两个条件都要**（`e.isComposing || e.keyCode === 229`）：后者覆盖「`compositionend` 之后才到」的那种 keydown，那时 `isComposing` 已经是 false。**不要**引入持久化的「组合中」布尔标志 —— 同第 9 条的理由。

    `commitEdit` 的重入闸（`if (!edit) return`）和「**先清位再 `call()`**」的顺序是照抄 [dnd.js](ui/dnd.js) 的 `finish()` 的，理由也同源：`call()` 返回触发的重绘必须是「已经不在编辑」的那一轮，否则会被自己的守卫跳过。编辑期间拖别的行会有一次「弹回旧顺序」的跳变，这是已知且刻意接受的代价 —— 另一条路（重绘后按 `edit.id` 重建输入框并恢复光标）要把正在组合的 input 从 DOM 摘掉，输入法组合态直接丢。

15. **开机自启动**（[autostart.rs](src-tauri/src/autostart.rs) + [ui/settings.js](ui/settings.js) 的 `renderAutostart`）。设置面板里一个开关，写 `HKCU\...\CurrentVersion\Run` 下的一条 `MiNo`。

    **注册表是唯一权威，`Settings` 上故意没有对应字段。** 两个理由：① 加 `Settings` 字段是这个仓库后果最重的错误类（见上面「改数据模型之前必读」）；② 一个持久化的 bool 天生会和注册表分叉 —— 用户在任务管理器里改一次就各说各话，而这个功能的全部意义就是如实反映系统状态。副作用是 `state.rs` 一行没改，`DATA_VERSION` 保持 4。**别为了「读起来方便」把它搬进 `Settings`。**

    五个必须记住的点：

    - **两条键一起看，缺一条就会骗人。** `Run` 是启动项本身，`...\Explorer\StartupApproved\Run` 是任务管理器的启用/禁用叠加层 —— 在任务管理器里「禁用」**不会删掉 Run 项**，只在那边写一条 12 字节的 `REG_BINARY`，首字节 `0x03`（`0x02/0x06/0x08` 是启用，`0x07/0x09` 是策略锁定；`0x08/0x09` 还表示「锁死改不动」）。只读 Run 项的话，用户明明关掉了、开关还显示「已开启」。
    - ⚠️ **两条读路径的失败方向相反，而且是刻意的，别去「统一」它们。** 主判据（Run 项）读失败 → 如实报「已关闭 + error」：谎报「已开启」会让用户永远不去查，自启动就静默坏了。叠加层读失败 / 长度不对 / 字节不认识 → **一律当作「没被禁用」**（同第 9 条 `foreground_is_fullscreen` 的规矩）：不确定的探测结果不能拿来*声称*一个更坏的状态，谎报「被禁用」会让用户去任务管理器里启用一个本来就启用的东西，他无从下手。
    - ⚠️ **开关的「开」必须同时清掉叠加层的禁用标记**（`clear_approval`），**只当那条值已经存在时**（不存在本身就是「启用」），且**只改首字节、其余 11 字节原样保留**（4–11 是 Windows 的 FILETIME 簿记）。不清的话会留下一个死胡同：用户点开关 → 写 Run 项（本来就在）→ 重读仍是 `blocked` → 界面诚实地显示「已关闭」，**用户点了一个没反应的开关，唯一出路是去任务管理器**。那不是不骗人，那是坏掉。反过来，**启动自愈绝不碰它** —— 那等于每次开机都把用户在任务管理器里的选择改回去。
    - **启动自愈（`self_heal`）只修路径**，放在 [main.rs](src-tauri/src/main.rs) 的 `window::init` 之后、`info!("MiNo 启动完成")` 之前。Run 项里是绝对路径，没有任何东西会自动更新它 —— 便携版挪个位置，自启动就永久失效，而面板还显示「已开启」。位置的三条理由：必须在 `logging::init` 之后（这条路径唯一的产出就是日志，早于 logger 的 `info!`/`warn!` 会被整个丢掉，同「接管数据目录」那个坑）；在 `window::init` 之后（注册表调用不受我们控制，放前面只会推迟用户看见窗口）；在事件循环起来之前（没有 IPC 会跟它抢读同一个状态）。**没事可做时一行都不记** —— 每次开机都跑，见第 13 条的频率纪律。
    - **`VALUE_NAME` 是常量，改名等于一次迁移。** 这个名字显示在任务管理器「启动应用」里给用户看，所以是产品名 `MiNo` 而不是 identifier。改它必须同时读新旧两个名字、并在写入成功后删掉旧名，否则老用户的开关会显示成关闭，再点一下又多一条重复项。
    - 路径**必须带引号**（`"C:\Program Files\...\MiNo.exe"`）：不带引号时 Windows 在第一个空格处切命令行。`current_exe()` 可能返回 `\\?\` 动词前缀，写进 Run 项是启动不起来的，要剥掉（UNC 形式要还原成 `\\srv\share\...`，直接砍 4 个字符会得到相对路径）。

    **本机没有 Rust 工具链，所以这个模块的逻辑全部抽成了纯函数**（路径比较、`REG_SZ` 编解码、叠加层字节判定、wide 串、日志清洗），`mod tests` 里 20 条断言覆盖它们 —— 那是 CI 上唯一能真正验证的部分。注册表 IO 在四个薄封装里，**一个测试都不碰真实注册表**。

    ⚠️ **`windows-sys` 的 FFI 签名在版本间改过，本机核不到。** 已用三件事规避：不 import `WIN32_ERROR`（错误码就地转 `String`）、不对 `HKEY` 调 `.is_null()`（只看返回码）、掩码常量绝不位或（只传单个）。第一次 CI 报错优先看 E0432（feature/导入路径）和 E0308（指针类型），最可能来回一次的是 `RegQueryValueExW` 的 `lpreserved` 是 `*const u32` 还是 `*mut u32`。

16. **任务提醒**（[reminder.rs](src-tauri/src/reminder.rs) + [ui/reminder.js](ui/reminder.js) + [ui/todos.js](ui/todos.js) 的浮层）。两种模式都**挂在任务上**（`Todo.remind_at` / `remind_mode` / `remind_date` / `reminded_on`），区别只是重复方式。这是**规格之外的新增**。

    **为什么挂在 `Todo` 上而不是一张独立的提醒表**：一张表要配自己的列表、增删改和排序 —— 那是规格 §1.2 非目标里「日历系统」的近亲。挂在任务上则仍然是「这件事」的一个属性。

    九件必须记住的事：

    - **调度是轮询 + 日期比对，绝不能用定时器。** 理由和跨日归档同源（应用可能休眠或隐藏数天），而且更极端：MiNo 的设计就是长期隐藏，而隐藏窗口里的 WebView2 计时器会被节流乃至冻结 —— **提醒最可能失败的场景恰恰是它最该工作的场景**。「到点」的判据是「第一个 `hhmm >= remind_at` 的轮询」，这顺带把「补提醒」做成了免费的。
    - **先落盘 `reminded_on`，再弹窗。顺序不能颠倒。** 反过来是「显示成功但落盘失败 → 30 秒后重弹一次」的无限循环；当前顺序最坏只是少提醒一次（下一轮重试）。
    - **`mutate` 那一步必须是无条件的**，不能照抄 `current` 里 `if rollover(...) > 0 { save }` 的形状 —— 否则每日提醒会一整天反复触发（第 1 条记过同一个坑）。
    - ⚠️ **`panic = "abort"` 让这个线程的爆炸半径是整个进程。** 没有 `catch_unwind` 可用，而它每 2 秒无人值守地跑一次、永远不停。所以 `tick`/`poll` 和它们调用的一切都必须**由构造保证不 panic**：不索引切片、不 `unwrap`、不裸 `+1`（用 `wrapping_add`）、锁一律 `unwrap_or_else(|e| e.into_inner())`。
    - ⚠️ **`read_state` 与 `mark_reminded` 必须顺序调用，不能嵌在同一个 guard 里** —— `DATA_LOCK` 是普通非重入 `Mutex<()>`，嵌套直接死锁。它们各自取放一次锁是对的。
    - ⚠️ **`on_window_event` 从此必须判断窗口标签。** 那个闭包原来建立在「整个应用只有一个 webview」这个前提上（注释里明写着），加第二个窗口直接推翻了它。不挡的话会立刻出三个 bug：浮窗的 `Focused` 被 emit 成 `mino://focus-changed(false)`，**主窗口以为用户切走了、自己收缩隐藏**；浮窗的 `Moved` 污染主窗口的吸附状态；浮窗的 `CloseRequested` 把它真的销毁掉，`get_webview_window("reminder")` 从此返回 `None`，**提醒静默地永久失效**。
    - ⚠️ **第二个窗口需要自己的 capability 文件**（[capabilities/reminder.json](src-tauri/capabilities/reminder.json)，`"windows": ["reminder"]`）。`default.json` 的 `"windows": ["main"]` 是白名单，标签不匹配的窗口**一个权限都拿不到**，而失败形态完全静默：页面收不到任何事件，浮窗弹出来是空白的。浮窗只给事件监听 —— 定位、改尺寸、显示、隐藏全在 Rust 侧（Rust 调这些不需要 ACL）。
    - **浮窗的 emit 只能用 `app.emit("<字面量>", …)`，不能用 `emit_to`** —— `tools/check-commands.mjs` 的 Rust 侧正则只认 `.emit(`，用 `emit_to` 会让前端的 `listen` 匹配不到、CI 直接红。
    - ⚠️ **提醒编辑浮层必须是 `#app` 的兄弟节点，不能挂到 `li` 下面。** 浮层里每次改动都会 `call()`，而 `call()` 返回后 `renderList()` 会 `replaceChildren()` 重建整个列表 —— 挂在行里就会被**它自己引起的**重绘当场销毁。而且它必须同时进两道闸：`state.ui.remindOpen` 要加进 `window.js` 的自动收缩抑制条件（鼠标移向时间框极易越出窗口），`window.js` 的全局 Esc 也要为它让路。**别指望浮层的 `stopPropagation` 挡住那个 Esc** —— 两个监听器挂在同一个 `document` 上，`stopPropagation` 只管「继续往外冒」，同元素上的其他监听器照跑（那是 `stopImmediatePropagation` 的事）。

    两个方向的取舍，都是刻意的：**迟到的提醒不设上限**（一条还在列表里的任务，晚上 11 点告诉你「今天这事还没做」仍然是对的），**认不出的 `remind_mode` 降级成 `once`**（一次性的打扰远小于天天误报）。同理，`is_daily` 要求**模式和时间同时成立** —— 手改文件改出来的「daily 但没时间」不会响，不给它免归档的待遇。

    **`rollover` 因此多了一个分支**：已完成的每日任务不归档，而是清掉 `completed` / `completed_at`、把 `created_date` 改成今天。**`order` 和 `created_at` 刻意不动** —— 用户拖出来的顺序是明确意图，每天悄悄重排等于跟他对抗；`created_date` 必须改，否则它会挂上 `[MM-DD]` 前缀，看起来像一条拖了很久的欠账。

    提醒**不被「前台有全屏程序」抑制** —— 那道门控是给悬停展开用的（鼠标扫过不该凑上去），而提醒是用户自己要的，被挡掉就是无声失效。

    **`remind_date` 可以是将来的一天**（「周一建的任务，周五早上九点提醒我」）。三条相关的约束：① 写入前必须过 `reminder::parse_ymd`，**归一化后不是 10 个字符就拒绝** —— `is_due` 判「是不是它那天」用的是 `remind_date == today` 的字符串相等，而 `today` 永远是 10 字符，5 位年份的日期会永远不匹配、静默地不响；② **过去的日期当场拒绝**（那种提醒永远不会响，是个静默失效），但只挡日期不挡时刻 —— 今天的 09:00 在 10:00 设下仍然有效，它会立刻响，那正是补提醒；③ **`daily` 一律把日期写空**，留着它会让 `is_due` 走 once 那条分支，每日提醒就变成一次性的了。

    **带提醒的任务不显示 `[MM-DD]` 日期前缀**（[ui/todos.js](ui/todos.js) 的 `hasReminder`）。那个前缀的语义是「这条你昨天没做完」—— 一个积压标记；而带提醒的任务是**排期**。一条排在周五的任务在周二显示 `[10-13]` 会让人以为它欠了三天。判据只看有没有 `remindAt`，两种模式一视同仁（每日任务永远不会「过时」，一次性任务在响过之后也不算积压）。

    ⚠️ **浮窗必须 `set_focusable(false)`，否则它会抢焦点并连锁出两个 bug。** 这是 2026-10-13 实机日志逼出来的，不是预防性的 —— 日志里完整出现过这一幕：

    ```text
    [23:43:54] 提醒触发 1 条
    [23:43:54] 窗口焦点离开
    [23:43:54] set_edge_collapsed collapsed=true  [focus-lost(...)]
    [23:44:06] 窗口焦点进入                       ← 正好 12 秒后 = 当时浮窗的自动收起时长
    [23:44:06] set_edge_collapsed collapsed=false [focus-gained(...)]
    ```

    浮窗一显示就激活了自己，主窗口于是以为用户切走了、当场收成 4px 感应条，浮窗自动消失后焦点回来又弹开 —— 看着像窗口在抽风。而且那 12 秒里**用户在别的程序里敲的键会进到浮窗去**，比收缩本身严重得多。原先的对策「不调 `set_focus()`」**不够**：Windows 上 `show()` 自己就会激活窗口。真正的开关是 `WS_EX_NOACTIVATE`，tao 的 `set_focusable(false)` 落的正是它（`!FOCUSABLE => style_ex |= WS_EX_NOACTIVATE`，见 tao 的 `window_state.rs`）。它在启动时设一次（`reminder::init`），不跟 `show()` 抢顺序 —— tao 那个调用是丢进事件循环队列的。

    另有两处连带后果，都是**有意保留**的：① `WindowEvent::Focused` 里加了一道 `!*focused && popup_may_have_stolen_focus()` 就直接 return 的第二道网（第一道生效时它走不到）；② 浮窗里的 Esc 处理因此永远不会触发（无激活就无键盘焦点），代码留着并注释说明，而不是删掉 —— 那个设置哪天被拿掉，它就是对的。Esc 仍然在用户原来那个程序里生效，这正是想要的。

    ⚠️ **那道第二道网的判据必须是「弹出后几秒之内」，不能是「浮窗正开着」。** 见下面「浮窗不自动消失」那条 —— 两者叠在一起会出一个很隐蔽的 bug。

    **浮窗不自动消失，也不该自己消失**（2026-10-16 改）。在此之前是 `DISPLAY = 12s` 到点自动收起，现在只有用户的主动动作才关得掉：点 ×（`dismiss_reminder`）或者点某一条（`open_reminder`，跳回主窗口并高亮）。这是用户明确要的 —— 提醒没被看到就等于没提醒，而 MiNo 的窗口平时是缩在屏幕边上的。

    四处连带改动，缺一处都会留下不一致：

    - **`HIDE_AT` 改成了 `SHOWN_AT`，`Instant` 的用途从「该收起的时刻」变成「刚弹出来的时刻」。** 名字不改会让人以为还有个定时器在读它。
    - **`tick_hide` 和整个「2 秒一跳、每 15 跳判一次」的节奏被删掉了。** 那 2 秒的精度原本只服务于「到点收起浮窗」，现在没有这个需求了，主循环退化成 `sleep(POLL)` + `poll()` —— 连带那个 `wrapping_add` 的跳数计数器一起消失。（轮询本身仍然必须是轮询：见本节第一条。）
    - ⚠️ **`FOCUS_GRACE`（3 秒）是必须有的上限。** 第 ① 条那道网要压制的是「浮窗刚弹出来那一下抢走了主窗口焦点」，而**焦点变化只发生在弹出的那一瞬间**。判据要是写成「浮窗正开着」，浮窗不消失之后它就会一直为真 —— 用户在浮窗挂着的整段时间（忘了关就是好几天）里 Alt+Tab 走开，主窗口都不会收缩成感应条，而且**越不理它越严重**。判据抽成了纯函数 `grace_holds`，`focus_grace_expires` 钉的就是「浮窗还开着但宽限期已过 → 不压制」。
    - **窗口尺寸整体翻倍**（`POPUP_W` 268→536，`POPUP_HDR` 34→68，`POPUP_ROW` 30→60，`POPUP_PAD` 14→28），`ui/reminder.css` 里那组同名对应的值（`--head-h` / `--row-h` / 上下 padding 各 14px）和**所有字号**一起翻。⚠️ **四个常量和字号必须一起动**：高度是算出来的，只翻一部分会裁掉内容或露出一块吃点击的透明区；只翻窗口不翻字号，得到的是一张空荡荡的大卡片，比原来更难读。

    ⚠️ **它常驻还意味着两件用户会撞上的事**，README 里写了、这里也记一笔：卡片是置顶的且**不会自己让开**，屏幕正中那块区域在被关掉之前一直点不进去；所以它不是「弹一下就走」的通知，是一个需要用户处理一次的东西。这也是为什么又一次 `poll` 弹出一批新提醒时（`show_popup` 再走一遍）会刷新 `SHOWN_AT` —— 宽限期跟着重新开始。

    **点 × 会把卡片上「仅一次」的任务标记成已完成**（2026-10-16 加）。「这条提醒我看过了」对一次性提醒就等于「这件事做完了」，省掉用户回主窗口再勾一下。六件事：

    - **只有「仅一次」的**。每日任务点掉一次不等于做完了，本来就不该划掉。判据用的是 `mode_or_once`，和卡片上那个圆点、以及 `is_due` 是同一个。
    - **只有点 × 才算。** 点某一行（`open_reminder`）的语义是「我去看看」，它走 [`hide_popup`] 而不是 [`dismiss_popup`]，**不动任何任务状态**。两个函数只差这一件事，所以调用点必须分清 —— 混用不会有编译错误，只会表现成「我只是想看看，怎么就被勾掉了」。
    - ⚠️ **要划的名单在 `show_popup` 里是和画内容同一个循环里收的**（`once_ids`），不是另起一遍 filter。另起一遍很容易忘记同样 `.take(MAX_ROWS)` —— 那样「还有 N 条」里用户根本没看见的任务会在点 × 时被悄悄标记完成。
    - ⚠️ **`PENDING_ONCE` 和 `SHOWN_AT` 同生共死**：`show_popup` 写、`hide_popup` 清。不清的话，上一次那批早已不在屏幕上的 id 还留着，下一次点 × 会按旧名单划任务。所以 `dismiss_popup` 的顺序是**先取名单再收窗口**（`hide_popup` 会清空它）。
    - **标记失败不回滚、不重试、不提示**：卡片已经收起来了，用户看到的就是「关掉了」。这时候弹一句「保存失败」是在为一次用户没要求过的副作用道歉。最坏是任务没被划掉，用户自己点一下圆圈就行 —— 他本来就有这个能力。
    - **改动条数为 0 时不发事件**（幂等：本来已完成、任务已被删、空名单都算没变化）。条数由 `state::complete_todos` 返回、经 `commands::complete_todos` 带回来，`mutate` 的闭包签名带不出返回值，所以是从闭包外面那个变量捞回来的。

    ⚠️ **新增的 `mino://todos-changed` 揭示了一个结构性缺口：主窗口不知道别的窗口写的数据。** `ui/state.js` 的 `state.data` **只在「主窗口自己调的那个 command 返回了快照」时才更新** —— 提醒浮窗调 `dismiss_reminder` 改了盘，主窗口的 JS 一无所知，不重拉就永远不会显示那条删除线。所以 Rust 侧改了数据要显式 emit、`ui/app.js` 监听后 `call('load_state')` 重拉。**以后任何「A 窗口改数据给 B 窗口看」的功能都得补这一环**，否则失败形态是「数据其实改了，界面没变」，看起来像没生效。

    ⚠️ 那个事件名**必须就地写字面量**，不能提成常量：`tools/check-commands.mjs` 的 Rust 侧正则（`/\.emit\(\s*['"]([^'"]+)['"]/`）只认 `.emit(` 后面直接跟的字符串。写成常量它扫不到，前端那条 `listen` 就成了「没有任何地方 emit」，CI 直接红（已经踩过一次：`TODOS_CHANGED` 常量刚写出来就被改回去了）。

17. **内存占用：合并两个渲染进程**（[main.rs](src-tauri/src/main.rs) 里那句 `std::env::set_var("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", …)`）。MiNo 是常驻几天的浮窗，用户会在任务管理器里盯着它 —— 所以哪一份开销是我们能动的，值得写清楚。

    ⚠️ **先记住口径**：任务管理器那一列的「内存」是**专用工作集**。用 `Get-CimInstance Win32_Process` 的 `WorkingSetSize` 读出来是 406 MB（它把共享的 Chromium 代码页也算进每个进程），和截图上的 89 MB 对不上 —— 量这件事必须用 `Win32_PerfFormattedData_PerfProc_Process` 的 `WorkingSetPrivate`，否则会得出「有三个 WebView2 应用」这种假结论（那几个 browser 进程其实是 SearchHost、GameViewer、Widgets 各自的）。

    2026-10-14 实测的账目：

    | 进程 | MB | 它属于谁 |
    |---|---|---|
    | browser | 30.3 | 固定地板 |
    | gpu-process | 23.1 | 固定地板 |
    | renderer（主窗口） | 16.2 | **能动的** |
    | renderer（提醒浮窗） | 10.2 | **能动的** |
    | utility NetworkService / StorageService | 5.0 / 2.5 | 固定地板 |
    | crashpad | 1.2 | 固定地板 |

    **约 62 MB 是「只要用了 WebView2 就必然有」的地板**，和窗口数量无关：一个 webview 也是这么多。能动的只有两个渲染进程那 26.4 MB。

    两个窗口都跑在 `tauri.localhost` **同源**，所以 `--process-per-site` 能把它们并进**一个**渲染进程。同一个可执行文件、同一台机器、只差这一个参数，背靠背各量一次：

    | | 进程数 | 渲染进程 | 合计 |
    |---|---|---|---|
    | 不带参数 | 7 | 2（19.9 + 13.8） | 102.5 MB |
    | `--process-per-site` | 6 | **1**（20.2） | **91.7 MB** |

    这是唯一一个**不改变任何功能**的降内存手段。

    ⚠️ **用环境变量，不要用 `tauri.conf.json` 的 `additionalBrowserArgs`。** 那个字段一旦设置就**整体替换**掉 Tauri 的默认值，而默认值恰好是 `--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection`（清掉 Edge 的分享 / PDF / SmartScreen 三条 UI，少了它们内存是往上涨的）—— 用它就得永远自己抄着那串、抄漏一次就是内存变多而不是变少（而且**这个键名在本机核不到**，没有 Tauri 源码也没有 `gen/schemas`）。环境变量是**追加**：实机核过，那三条原样还在，我们这一条叠上去。

    ⚠️ **那句 `set_var` 必须是 `main()` 的第一件事。** WebView2 的**环境**在第一个 webview 被创建时才建立（下面的 `.build()`），那一刻之后再设就完全没用了。也还好在那里 —— 此刻进程还是单线程的（edition 2021 下 `set_var` 是安全函数，不用担心 E0133）。

    ⚠️ **别为了省这 10 MB 把提醒浮窗改成原生 Win32 窗口。** 该省的和 `--process-per-site` 一样多，代价却是重写渲染、DPI 和命中测试，而 62 MB 的地板一分不动 —— 那是笔亏本账。同理，`--disable-gpu` 看着能砍掉整个 GPU 进程，实际是把合成挪回 CPU，透明窗口和 `backdrop-filter` 的观感先变了，得不偿失。

    ⚠️ **这个开关的副作用要知道**：两个窗口从此共用一个渲染进程和一条主线程。提醒浮窗的重绘不再和主窗口隔离 —— 主窗口那边一次 `replaceChildren()` 理论上会推迟浮窗上屏。对 280×380 的列表可以忽略，但**如果哪天上机后看到「弹窗迟一拍」或「一个窗口卡住另一个也没反应」，第一个要怀疑的就是它**（回退方式：删掉那句 `set_var`）。

## 不可违反的行为约定

- **关闭按钮 ✕ 只隐藏窗口，不退出进程**（规格 §16）。真正退出只经「托盘 → 退出」，通过 `state::QUITTING` 放行 `RunEvent::ExitRequested`。窗口全关也不退出，否则托盘和快捷键会一起消失。
- **Todo 文本禁止 `innerHTML`**（规格 §20.1），一律 `textContent`。验收：输入 `<img src=x onerror=alert(1)>` 必须原样显示。
- 添加后保持输入框 focus，不弹通知、不弹确认框；删除和清空已完成都不弹确认。
- 字体链、归档等写入用户可见文本的地方，用户文件名同样是不可信字符串。

## 容易踩的 Tauri 2 细节

- **`emit` 在 `Emitter` trait 上，不在 `Manager` 上**，两个都要 `use`。
- **`TrayIcon` 没有 `menu()` getter**，只有 `set_menu` —— 菜单建好就拿不回来了，`AppHandle::menu()` 取的是应用菜单、不是托盘菜单。要回写 `CheckMenuItem` 的勾选状态只能自己留一份句柄，见 [tray.rs](src-tauri/src/tray.rs) 的 `ONTOP_ITEM`。
- 自定义 command **不需要** ACL 条目（本地 origin 默认放行）；但 `core:window:allow-*` 那一串是必须的，`core:window:default` 只有只读的 getter。`core:window:allow-start-dragging` 漏了 `data-tauri-drag-region` 会静默失效；而这个属性**必须挂在真正被点到的那个元素上**，光挂在祖先节点上不算数（见第 11 条）。
- `transparent: true` 必须配 `shadow: false`，否则窗口阴影会让透明失效。
- 窗口在配置里是 `visible: false`，由 `window::init()` 定位后再显示 —— 否则会先在屏幕中央闪一下再跳到右上角。
- `app_data_dir()` 取的是 `identifier`（`com.mino.app`），不是 `productName`（`MiNo`）。
- 字体枚举的 `font-kit::SystemSource::all_families()` 在部分系统上只返回本地化名，所以 `list_families()` 会把一份兜底清单（含英文名）并进去，且失败时降级而非报错。

## 与规格的偏离

六处有意为之，改回去前请先确认收益：

1. **用 `<input type="file">` 而不是 `tauri-plugin-dialog`。** 插件的 JS API 不在 `withGlobalTauri` 的全局包里（插件是独立 npm 包，而本项目没有打包器），vanilla JS 调不到；Rust 侧的 `blocking_pick_file` 又有主线程死锁风险。标准文件选择器少一层依赖，且不涉及任何权限配置。
2. **`ui/` 用 ES modules 拆成多文件**，而不是规格 §19 里那张表 —— 大体一致，但多了 `boot.js`（首屏字体引导）和 `settings.js`（设置面板），以及提醒浮窗那一套 `reminder.html` / `reminder.js` / `reminder.css`（见第 16 条）。
3. **字体选择器是自己实现的 listbox**，不是原生 `<select>`：Windows 上展开的列表由系统绘制，会忽略 `<option>` 上的 `font-family`，预览会全部失效。
4. **打开外部链接走 `cmd /C start`，而不是 `tauri-plugin-opener`。** 理由和上面第 1 条同源：插件的 JS API 不在 `withGlobalTauri` 的全局包里，前端调不到，而为了一个「打开 Release 页面」再引一个 Rust 依赖不划算。代价是那个 command 自己承担 URL 校验的责任（见第 10 条），**字符集白名单不能删**。
5. **开机自启动自己写注册表，而不是 `tauri-plugin-autostart`。** 那个插件只有「写 Run 项」这一半，读不出任务管理器写在 `StartupApproved` 里的禁用标记 —— 而「读得出来」正是这个功能的全部要点（见第 15 条）。`windows-sys` 本来就在依赖里，加一个 feature 的代价远小于多一棵依赖树。另外它也是**规格之外的新增**：规格 §1.2 的非目标里没有它，§7 那张 UI 表里也没有设置面板。
6. **提醒是规格之外的新增，而且刻意没做成一张独立的提醒表。** 规格 §1.2 的非目标里没有它（列的是云同步、账号、日历、标签、Markdown），但规格也从没描述过它。做成「任务的属性」而不是「一张闹钟表」的理由见第 16 条。另一个明确的非目标：**不做系统级计划任务** —— 提醒只在 MiNo 运行时生效，这一点写进了 README 的已知限制。

## 验收

规格 §27–33 是验收用例（基础 Todo / 日期 / 背景 / 快捷键 / 边缘吸附 / 数据损坏 / 安全），§36 是 v0.1 的 Definition of Done 清单。

**所有窗口行为必须在 Windows 实机验证** —— 尤其透明窗口的圆角、点击区域、拖动区域，以及边缘吸附缩成 4px 感应条后能否稳定被鼠标唤起。规格 §34 第 6 条：不要因为原型 demo 能跑就认为产品完成。

**开机自启动同理，而且更极端**：`cargo check` 只证明它编译得过、`cargo test` 只证明那几条纯函数对。值有没有真的写进 Run 项、注销后是不是真的启动、任务管理器里显示成什么、我们手写的 `0x02` 字节 Explorer 收不收 —— 一条都验不了。最要紧的两条实机用例是：**在任务管理器里禁用 MiNo 之后，设置面板必须显示「已关闭」而不是「已开启」**（这条错了整个功能就是摆设），以及**在面板里点一下开关必须能把它恢复回来**。

**提醒又是另一类盲区**：时间到了会不会响、浮窗有没有真的显示文字、`<input type="time">` 在深色半透明 chrome 下长什么样 —— 本机一条都验不了，而且**第二条的失败是完全静默的**（capability 漏了就是一张空白卡片，除了控制台什么都没有）。实机验收里最要紧的四条：① 浮窗真的显示了任务文字；② **弹窗出现时主窗口不该收缩或隐藏**（这条验的是 `on_window_event` 那道标签闸，没有它弹一次提醒主窗口就自己躲起来）；③ 设了每天的提醒，退出 MiNo 再启动**同一天内不该再响**（验 `reminded_on` 真的落盘了）；④ 勾完成一条每日任务、把系统日期改到明天之后，它**仍在列表里、未完成、且不带 `[MM-DD]` 前缀**；⑤ **浮窗弹出来之后不主动关就一直不走，而弹窗开着时 Alt+Tab 走开、主窗口仍然照常收缩成感应条** —— 后半句验的是 `FOCUS_GRACE`，写错了表现是「有个提醒没关掉，之后切走就再也不收缩了」，很容易误判成别的问题；⑥ **点 × 关掉卡片后，主窗口里那条「仅一次」的任务立刻变成已完成**（打勾 + 删除线 + 沉到底部）—— 这条同时验两件事：`dismiss_popup` 真的标了，以及 `mino://todos-changed` 真的把主窗口叫醒重拉了。**只验「任务在数据里被标了」是不够的**，界面不刷新同样表现为「没生效」。顺带验两个例外：**「每天」的任务不该被勾掉，点某一行的文字也不该勾掉**（后者走的是 `hide_popup`）。

## 沟通语言

规格、UI 文案与注释均为中文，回复用户时使用中文。
