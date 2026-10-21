# Agents Anywhere 手表版 — 项目约束

本仓库是 [dsh-watch](https://github.com/DING-HAO-RAN/dsh-watch) 的改造分支：手表端从「局域网中继客户端」改为 **Agents Anywhere（AA）云 / 自托管服务器的客户端**（方案 A）。中继、UDP 发现、配对整条链路已删除；`desktop/`、`relay/`、`tests/` 是原方案的遗留代码，仅供参考，不参与手表构建，**不要再往里面投入修改**。

## 构成

- 唯一的有效部件是 [app/](<app/>)：原生 Java 手表应用，包名 `cn.dsh.watch`。
- **HTTP 层集中在 [Api.java](<app/src/main/java/cn/dsh/watch/Api.java>) 一个文件**，界面代码不直接碰 URL。想换自托管服务器，改登录页里的地址即可；改协议时只动 Api.java。
- [Store.java](<app/src/main/java/cn/dsh/watch/Store.java>) 存的是**账号**（base + email + password + token + userId + displayName + 备注），不是设备列表。`Store.parseServer` 是地址解析的唯一入口，**不要再引入第二套地址存取逻辑**。
- 换账号（`switchAccount`）时必须清空：会话、`steps`、`openGroups`、`openRows`、`lastListSignature`、待答通知与模型目录，否则上一个账号的状态会套到新界面上。
- 「记住密码」默认开启：AA 令牌 7 天 TTL 且**无刷新接口**，静默重登依赖本机保存的密码。关闭后令牌过期只能手动登录。

## AA 协议（实测结论，改动前先复核）

- 登录：`POST /api/v2/auth/password-salt {email}` 取盐 → `passwordVerifier` = PBKDF2-HMAC-SHA256（密码、盐 UTF-8、**120000 轮**、32 字节）→ base64url 无填充 → `POST /api/v2/auth/login {email, passwordVerifier}` 换 `{userId, accessToken, ...}`。7 天 TTL。401 时静默重登一次，再失败清令牌回登录页。
- 每个请求都带 `Accept: application/json` 与 `ngrok-skip-browser-warning: true`（自建服务器架在 ngrok 后面时没有它会被拦成网页）；JSON body 加 `Content-Type: application/json; charset=utf-8`；空 POST body 用 `{}`。
- FastAPI 错误是 `{"detail":...}`；RPC 风格响应在 HTTP 200 里带 `{ok, result, error}`，`ok:false` 不是网络失败。两者都要翻译成中文给用户。
- REST 列表响应有包裹键：`sessions` / `projects` / `connectors` / `runtimes` / `state` / `notices` / `catalog`（模型目录外层多一层 `catalog`，Api 已解包）。`POST /sessions/read` 的 body 是**纯 JSON id 数组**。
- 新建会话走 `POST /sessions/create-and-start`，服务器要拉起运行时，**读取超时放宽到 75 秒**。
- **两个独立的序列域**：增量轮询游标是时间线信封的 `nextSeq`（事件序号）；翻历史用条目上的 `orderSeq`（最早一条减 1 往回翻）。二者互不通用，混用会把历史翻页弄乱。
- 提问两类回法：`input_request` 表单回 `{answers:{问题id:{optionIds,customText?}}}`，`actionId` 用表单自带的；审批/确认类没有表单，**选项 id 就是动作 id**。只有 `type=="interaction"` 且 `responseRequired` 且状态 `open|responding` 的通知可答。
- 模型目录 `/runtime/catalogs/model` 给 `{models:[{selectionId, reasoningItems:[...]}]}`；当前生效值以会话状态的 `selections` 为准，目录 `default` 只做初次兜底。切换要先 `POST /takeover` 再 PATCH selections；这条链路**刻意不做 401 静默重登**，失败直接报给用户。
- **null 陷阱**：服务端文案字段可能显式为 `null`，`org.json` 的 `optString` 遇 null 返回 null，所有解析必须经 `Api.text(...)` 兜底，否则界面会崩。

## 构建

- 构建走 [scripts/build.ps1](<scripts/build.ps1>)，直接调用 SDK 工具链（aapt2 → javac → d8 → zipalign → apksigner），无 Gradle。环境用 `JAVA_HOME` / `ANDROID_HOME` 指定，**不要把本机路径写进仓库**。
- `BuildRoot`（或 `WATCH_BUILD_ROOT`）**必须在项目目录之外**，脚本会拒绝在工作树里构建——避免把临时产物和签名私钥混进版本库。
- 脚本是 UTF-8 **带 BOM**：Windows PowerShell 5.1 读无 BOM 文件会按 GBK 解码，中文注释直接变成语法错误。改动脚本时保留 BOM。
- 三个必须保留的写法（去掉就会踩坑）：
  1. 外部命令输出重定向到文件后判退出码——PowerShell 5.1 在 `ErrorActionPreference='Stop'` 下会把 javac 的过时警告当致命错误中断构建，从而**掩盖真正的编译错误**；
  2. 源码与 class 清单写文件后用 `@file` 传参——数组经 shell 展开会被拆坏，导致 d8 静默漏掉大部分类，手表上表现为 `NoClassDefFoundError`；
  3. 用 JDK 的 `jar` 工具把 dex 追加进包——受限语言模式下不可用 .NET 的 ZipFile。
- `build.ps1` 会逐个核对 `MainActivity` / `WatchUi` / `WatchUi$Page` / `Store` / `Api` 五个 class 是否存在，这是拦截「类漏编」的最小门禁。新增顶层类时同步维护这个清单。

## 界面

- 圆屏安全区由 [WatchUi](<app/src/main/java/cn/dsh/watch/WatchUi.java>) 的 `page()` 一处负责：按圆的内接矩形留边（左右 15%、上下 18%）。**整个滚动区都在圆内，可见内容天然安全**，页面不要自己再加边距。
- **绝不要按控件自身的屏幕位置去动态调边距**。它形成布局反馈环——算边距 → 控件变窄 → 文字折行 → 位置变化 → 再算出不同边距，最终把同排按钮压成宽窄不一的窄条且无法拉伸。基础安全区已经足够。
- 按钮用 `TextView` + 自绘背景实现（`WatchUi.button`），**不用 `android.widget.Button`**：后者带 9-patch 背景与固有最小宽度，在窄圆屏上会被顶住而撑不满整行。按钮必须单行显示，宽度交给父容器（`LayoutParams(-1, -2)`）。
- 同排两个按钮用 `weighted()`（`weight=1` 平分）；**之后不要再改它们的 layoutParams 或加 margin**，那会破坏 weight 分配。
- 可点区域不低于 48dp；**操作入口尽量收进左滑菜单**，页面上只留最常用的两个按钮，把高度让给内容。
- 标题必须单行（`WatchUi.headingView` 已设 `setSingleLine`）。圆屏可视高度只有三百多像素，标题一旦折成两三行，按钮和内容区就被挤出屏幕。截断用 `clipLine`（只加省略号），**不要用会插换行的写法**。
- **绝不能让「渲染 → 发请求 → 回调再渲染」形成环**。列表页曾经如此：`showSessionList()` 里发请求，回调又调 `showSessionList()`，屏幕每轮都重建、持续上下抽动。现在的分工是：`showSessionList()` 只在进入页面时调用一次，回调一律走 `renderSessionListPage()`（只重绘、不发请求）。
- 界面用「重建整屏」的方式刷新，因此**滚动位置由 `render()` 统一保留**，换页时才调 `resetScroll()` 回顶部。会话页（4 秒）与会话列表（10 秒）都会定时刷新，不做这件事用户就翻不动列表和长对话。
- 刷新前先比对**内容指纹**，没变化就直接返回。定时刷新还额外受两道闸约束：用户刚触摸过（2.5 秒静默期）、或正在编辑时跳过。
- **需要用户停留的页面**（发消息、打字回答、选项答题、模型选择）必须先置 `editing = true` 暂停自动刷新，否则刚打开的界面会被立刻换掉。
- 服务端 JSON 的文案字段可能显式为 `null`；`Api.text(...)` 兜底是硬性规则（见协议一节）。

## 手势与操作菜单

- 横向手势在 `MainActivity.dispatchTouchEvent` 里判定，**不能**挂在页面根容器上——子层 `ScrollView` 会先消费触摸，左滑会被当成纵向滚动。
- 判定要用**整个手势期间的最大位移**，不能只看抬手那一帧：注入的滑动常被拆成很多小步，逐帧看会漏判。门槛为横向 ≥44dp 且大于纵向的 1.2 倍。
- 约定：**右滑返回上一级，左滑打开操作菜单**。方向必须一致——左滑是「把右侧的内容拉出来」，因此菜单面板**贴右边缘、从右滑入**（140ms 位移 + 淡入）；面板放左边会让人觉得手势反了。关闭用 150ms 反向动画，动画结束才移除视图并允许下一次打开。
- 正文与工具结果都经 [Markdown](<app/src/main/java/cn/dsh/watch/Markdown.java>) 渲染：标题放大加粗、列表带圆点、行内代码等宽加底色、引用变灰、表格压成一行、代码块整体等宽。工具读出的 .md 文本因此能直接可读。
- 工具调用按**正文分界**分组，不是按「相邻的工具步骤」：时间线里工具与正文平铺、中间几乎总隔着一条消息步骤，按相邻关系分组会让每次调用各自成组，折叠就失去意义。折叠分两级——点卡片列出连续调用的清单，点清单里某一条才看参数与结果；收组时连带清掉内部明细，下次打开是干净的清单。
- **展开状态必须记在 `openGroups` / `openRows` 里**（工具组键 `groupKey`，行键 `groupKey#i`，思考块键 `think:<seq>`）。界面是重建整屏刷新，展开状态若只存在于视图上，会话页每 4 秒一次的刷新就会把用户刚点开的详情缩回去——这正是「点了展开却看不到内容」。还原直接设可见性、**不走动画**，否则整屏重建时每张卡片都闪一遍。
- 收组监听器只挂标题与摘要两处，**不要挂整张卡片**：清单区域必须留给单条明细，否则「点这一条」被卡片抢走，变成把整组收起来。清单行整行可点（`rowBox`，`minHeight 48dp`）。
- 模型选择两级（模型 → 推理强度）：AA 目录没有供应商层级，Api 把整个目录包成一个名为「模型」的 `ProviderInfo` 以复用既有结构；显示时不要把它再拆成三级。当前值取「会话状态 selections → 目录 default」，不存在时不臆造模型名。
- 长路径会占满圆屏，展示前一律经 `shortenPaths` / `shortPath` 压成最后一段。

## 测试

- 目前没有自动化测试：原 `tests/` 下的脚本都是对着**中继**跑的，不适用于 AA。协议层的核对靠 [Api.java](<app/src/main/java/cn/dsh/watch/Api.java>) 里的注释与 AA 手机客户端 / 网页端的实际行为对照。
- 涉及界面行为的改动，用 `adb` 在真机上从实际入口走一遍（启动 → 登录 → 点项目 → 点会话 → 发消息 / 答题 / 切模型）。
- 手表上验证输入框内容要看截图：`uiautomator dump` 对第三方 `EditText` 常读到空值。
- 验证「界面是否还在闪」不要靠肉眼看截图：间隔几秒连拍数张，比对文件哈希；哈希一致即画面稳定。
- 没接 adb 时，构建通过 + 代码审查是最低门槛；**每次改完必须跑 `scripts/build.ps1` 确认零编译错误**。

## 发布

- 仓库：[DING-HAO-RAN/dsh-watch](https://github.com/DING-HAO-RAN/dsh-watch)（上游，MIT）。本分支的产物名固定为 `AA手表.apk`（[README](<README.md>) 与之一致）。
- 仓库用 **MIT 协议**（[LICENSE](<LICENSE/>)，版权署名用仓库标识 `DING-HAO-RAN`，不写个人姓名与邮箱）。改协议要同步三处：`LICENSE`、README 的许可段、`relay/package.json` 的 `license` 字段。
- 成片与封面放在 `docs/`（**不进版本库**，`.gitignore` 已忽略）；投稿文案在 `docs/B站投稿文案.md`（进版本库）。简介正文距 B 站 2000 字上限留余量，再加内容先删同量的旧内容。
- **画面不许用 CSS 动画**：逐帧截图时 CSS 动画不受控，同一 `t` 会截到不同画面。所有随时间变化的量都必须由 `t` 直接算出来。
- 旁白每行 ≤30 字、语速 `speed=-6`；字幕直接用旁白原句（静音也要能读懂），画面上的大字只写旁白里没有的事实，两者刻意不重复。

## 敏感信息

- **绝不把用户凭据（邮箱、密码、令牌、userId）写进代码、git、日志或回复**。账号数据只存在手表本地 SharedPreferences（应用私有目录）。
- 仓库里的 `desktop/`、`relay/`、`tests/` 属于旧方案，其中可能引用本机路径或历史令牌，不要把它们的内容当作现状。
