# ME Transfer Drive：性能、AE 网络开销与数据一致性专项审计

审计日期：2026-10-27。目标：GTNH 2.9.0-RC-2、Minecraft 1.7.10、AE2 `rv3-beta-1080-GTNH`、Thaumic Energistics（ThE）`1.7.64-GTNH`。

## 0. 范围、证据与结论标记

本次仅分析并新增本报告，没有修改 Java、测试、Gradle、配方、GUI、运行逻辑或开发状态，没有执行构建、自动格式化、游戏测试或性能基准。现有 228 项测试的通过记录属于上一轮验收，不能视为本次发现已被验证或排除。已阅读指定的五份项目文档，但以下结论以实际代码为准；没有读取环境排错归档。

- **[确定]**：可直接从当前源码确认的控制流、常量或行为。
- **[推导]**：在明确前提下，从源码推导的数量、复杂度或可达后果；没有实测耗时。
- **[待测]**：需要最终整合包、运行配置、第三方实现或故障注入才能确认。
- **P0/P1/P2**：后续处理建议的优先级，不表示本次已授权修改。

项目基线为 `61ac7830ded1cc3de5dfc12eb2367ddcac23f65a` **加审计开始时工作区中的未提交修改**，不是仅审查该提交。引用行号来自这一工作区。

AE2 证据取自本地固定版本源代码 JAR，而非网上 master：

```text
.gradle-user-home/caches/modules-2/files-2.1/com.github.GTNewHorizons/
Applied-Energistics-2-Unofficial/rv3-beta-1080-GTNH/
ffcd0d2e628fe990bfa902baba8bbd2dcb1c8f60/
Applied-Energistics-2-Unofficial-rv3-beta-1080-GTNH-sources.jar
SHA-256: e1c6ec53c8f8d2e70c3cb79f095a32786cbb4b8449ebe8c040ce8c103706d9e8
```

ThE 核对本地 `1.7.64-GTNH-dev.jar`（SHA-256 `3952ae0afbc8878468bb987a56cf95064feb95411263775e71cf4431066c84ae`）及现有 `build/reference/thaumicenergistics/` 中对应固定 tag 的源码，并对照其类继承、方法及字节码行表。上游定位链接：[AE2 固定 tag](https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial/tree/rv3-beta-1080-GTNH/src/main/java)、[ThE 固定 tag](https://github.com/GTNewHorizons/ThaumicEnergistics/tree/1.7.64-GTNH/src/main/java)。本地原版/Forge 路径仅代表开发环境中的实现，GTNH 在运行时施加的 mixin、配置或其他补丁须另行验证。

### 0.1 源码引用约定

为避免表格反复出现长路径，后文使用以下前缀，`文件:行号` 均为源码行号，而非本文行号：

| 前缀 | 实际路径 |
| --- | --- |
| `M/` | `src/main/java/com/kyou/metransferdrive/` |
| `AE/` | 上述 AE2 源码 JAR 内的 `appeng/` |
| `TE/` | 固定 tag 的 `src/main/java/thaumicenergistics/common/`；本地参考文件见上一段 |
| `MC/` | `build/rfg/minecraft-src/java/net/minecraft/` |

例如 `M/tile/TileMETransferDrive.java:183–215` 明确指向本项目的该文件与 `onChangeInventory()`；上游可按固定 tag 和相同包路径复核。

## 1. 执行摘要

### 1.1 总体判断

1. **[确定] 8 组路由不是 8 个网络设备。** 本机只有一个 `AENetworkProxy`/源 Grid 节点，一个频道需求，一个 `ICellContainer`，静态设备闲置耗电基数为 2 AE/t；64 个有效元件仍分别创建并注册原生 Handler。节点开销接近一台原生 Drive，元件存储维护成本接近相同 64 元件的 7～8 台 Drive，不能统一称为“一台原版驱动器的成本”。
2. **[推导，P0] 存在条件可达的同元件双 Handler 缓存分叉。** 插拔回调中重建并发布给 Grid 的 Handler A，可能在紧随其后的 `slot=-1` 回调后，被本机内部转运使用的新 Handler B 替代；Grid 仍可保留 A。A/B 共享 NBT、数量缓存独立，合法返回值也可能造成复制或丢失。详细触发链和算例见 4.1；**尚未进行游戏复现，不能写成必现故障，也不能据现有单元测试宣称已排除。**
3. **[确定] 关闭全部组不一定只剩计时器。** 无目标绑定时主要执行每 Tick 的常量判断和计时器；有绑定但全部组关闭时，每周期仍解析全部绑定并遍历 64 个槽位索引，不枚举元件内容、不构图。已有文档中的“空闲无目标解析”须按此分支区分。
4. **[确定] 原生 Drive 不是完全不 Tick。** 它通过 AE Tick 服务每 15 Tick 检查 10 槽显示状态；不会因此每 15 Tick 重建元件 Handler。本机取消动态灯色，但增加每世界 Tick 的转运控制器入口、周期解析及按需循环图。
5. **[确定] 事务保护主要保障运行时的保守处理，不提供跨库存原子提交。** 已知回填失败保留 `RECOVERABLE`，未知结果隔离且不自动补发；`markDirty` 不是同步落盘，正常结束为 IDLE 后也没有跨源/目标存档的提交记录。硬崩溃仍可能造成复制或丢失。
6. **[确定] 搬运先按模拟授权量实际预扣源网能量，不退款。** 完全接收时，4096 物品、64000 mB、4096 源质分别为 `4096M`、`64M`、`4096M AE`，其中 `M` 为运行配置的能源倍率。实际拒收/少提取/部分接收及多目标回退可比原生 IO Port 付出更多能源。
7. **[确定/待测，P1] 每 Tick 一笔事务不等于固定 CPU 时间。** 一笔可进入整个目标网的 Handler 链、监听器和外部容器。循环图的 4 工作单位预算不覆盖全部证明复核；一次可复核最多 704 个见证，普通完整事务还会多次复核最多 64 个目标节点。
8. **[待测，P1] 恢复物品聚合 NBT、上游异常深度状态及容量统计兼容值得优先验证。** 固定 AE2 对异常 Handler 的部分计数/栈缺少 `finally`；本机的隔离不能修复这些上游状态。原生容量统计只识别 `TileDrive/TileChest`，本机不计入该统计路径，尽管其实际库存仍被注册。

### 1.2 安全保证的边界

本报告**不认定当前版本可无条件保证无复制、无损毁或任意第三方兼容**。在 4.1 的缓存一致性前提得到确认前，单批事务的局部数量等式不能提升为整个设备库存的守恒证明。优先建议先复现并处理这一数据一致性问题，再评价优化收益；其余优化应由实测决定，不因“存在可优化项”就立即实施。

## 2. 原生 ME Drive / ME IO Port / 本 Mod 对比

| 维度 | 原生 `TileDrive` | 原生 `TileIOPort` | `TileMETransferDrive` / 控制器 |
| --- | --- | --- | --- |
| 存储定位 | 持续向本 Grid 提供已安装元件 | 输入/输出元件的 I/O 工位；临时元件不作为 Drive 的 provider 注册 | 源网原生 Drive，同时从本机元件向独立目标 Grid 搬运 |
| 实体元件槽 | 10 | 6 输入 + 6 输出；另有升级/配置状态 | 64，8 组只用于路由 |
| 节点/频道 | 一个代理节点、一个频道需求 | 一个代理节点、一个频道需求 | 一个代理节点、一个频道需求；绑定不创建跨网连接 |
| 元件库存缓存 | 按类型 `cellsMap`，每槽原生 Handler 外包 `DriveWatcher` | `currentCell` + `cachedInventory`，只缓存当前元件，切换引用时重建 | 按类型 `cellsByType` + 64 `handlersBySlot`，外包 `DriveCellHandler` |
| 原生 Handler 数量 | 每个有效元件一份 | 当前正在处理元件的一份 | 每个有效元件一份，最多 64；参见双缓存风险 |
| 自身调度 | `IGridTickable` 每 15 Tick，显示/active 检查 | `TickRates.IOPort` 默认 1～1 Tick，可配置；工作/红石/库存驱动唤醒或休眠 | `@TileEvent(TICK)` 每服务端世界 Tick；默认 1200 Tick 到期，执行多 Tick 作业 |
| 关闭/无工作 | 仍有注册期间的 15 Tick 显示检查 | 无工作可 SLEEP；断电等返回 IDLE | 无绑定常量路径；有绑定全关闭仍周期解析，详见第 6 节 |
| Handler 结构变化 | 库存回调失效/重建；发布 CellArrayUpdate | 输入元件切换重建当前缓存 | 插拔/恢复使全部 64 槽缓存失效并重建；数量变化本身不应重建 |
| 网络数量通知 | 网内正常操作由 monitor 通知；插拔额外 `Platform.postChanges` | EMPTY 来源为未注册的临时元件，无需为它发布源网 delta；接收 monitor 通知 | 内部提取/回填绕过源网统一库存，因此手工发布源网 delta；目标由 monitor 通知 |
| 类型 | 注册元件类型；原生驱动器机制可承载扩展类型 | `getInv()` 遍历注册类型，泛型 I/O 可处理已注册源质 | 普通存储遍历注册类型；转运明确限物品/流体/注册源质且来源满足能力检查 |
| 搬运能量 | 自身不主动搬运；正常网络操作由调用者计费 | `Platform.poweredInsert`：先模拟能量，实际接收后按成功量扣费 | 先模拟目标，源网按授权量实扣，后提取/注入，不退款 |
| 失败回填 | 不适用主动搬运 | 调回源库存，但忽略回填返回余量 | 检查返回量；保留已知余量或隔离不确定结果 |
| 崩溃事务 | 无跨库存事务 | 无跨库存提交日志 | 有单批证据但不是持久 WAL，也不是两阶段提交 |

主要依据：`AE/tile/storage/TileDrive.java:200–207,267–292,322–385`；`AE/tile/storage/TileIOPort.java:282–317,352–480,488–569`；`M/tile/TileMETransferDrive.java:59–143,183–215,466–536`。

### 2.1 原生 Tick 与事件的实际关系

- **Drive**：`getTickingRequest(15,15,false,false)`，回调仅 `recalculateDisplay()`；每次检查 10 槽状态/类型。供电、频道事件也调用该方法，active 真正变化时才发 CellArrayUpdate；显示变化才 `markForUpdate`。频率是其在有效 AE Tick 服务中注册时的 15 游戏 Tick，不是离网/卸载后继续运行的保证。普通元件数量变化经原生保存回调写元件 NBT，不直接逐次重建整个 Drive。
- **IO Port**：`AE/core/settings/TickRates.java:29,55–60` 的默认 IOPort 为 `(1,1)`，配置可以覆盖；工作返回 URGENT，经 `AE/me/cache/TickManagerCache.java:103–105` 和 `AE/me/cache/helpers/TickTracker.java:50–59` 限到最小间隔。`TileIOPort.updateTask():197–206` 根据工作状态唤醒/休眠，库存、配置、Grid、红石变化触发它；无工作返回 SLEEP。不能把“默认每 Tick”写成运行配置不可变，也不能把 SLEEP 说成每 Tick 扫六槽。
- **本机**：`AE/tile/AEBaseTile.java:147–151` 分发世界 Tick；本机 `tickTransfers():125–129` 在 ready、服务端且未失效时调用控制器，独立于是否有工作/源网是否在线。所有到期、图、转运都在主线程顺序执行；没有后台转运线程。

### 2.2 AE 原生库存缓存与通知

`ICellContainer` 也是 `ICellProvider`。本机并未另造总库存：Grid 按类型取得它的独立原生 Handler，加入 `NetworkInventoryHandler` 的优先级链。64 槽不等于每次正常注入扫描 64 槽，但最坏的目标/来源 Handler 尝试仍与数量相关。

`AE/me/cache/GridStorageCache.java:237–273` 的 CellArrayUpdate 是**整个源 Grid 的结构失效事件**：清空已建的各类型网络 Handler，检查 provider 的 active 状态，forceUpdate monitor。随后 `getInventoryHandler():298–302 → buildNetworkStorage():286–295` 才按需遍历 active provider 和 Handler。这个事件不只是本机的轻量本地标记。

普通数量变化走 `AE/me/cache/NetworkMonitor.java:191–220,260–350`，标记清单变化、通知监听者；有相关 ItemWatcher 时会查询该类型的当前数量。`getStorageList():174–187` 在失效后按需重建清单，**不是所有注入都无条件遍历全网全部库存**。本机 `sourceChanged():310–326` 的显式 delta 是绕过源 monitor 的内部元件操作所必需；通常不是重复发同一源 monitor 的通知，但“提取后又回填”会产生两个方向的独立通知。

## 3. 状态维护清单与文字调用流程

以下对象上限均为**单台设备/单个 Container/单次作业**，不是全服务器上限。除特别标明的 Netty 解码和客户端 UI，世界、Grid、原生库存与服务端管理均在游戏主线程调用。

### 3.1 存储、网络与配置状态

| 状态及用途 | 实际触发与频率 | 数量、缓存与失效 | 开销和证据 |
| --- | --- | --- | --- |
| 64 元件实体槽 | 插拔、GUI/自动化库存操作；世界 NBT 读写 | 固定 64 个 ItemStack 引用；每槽一个元件；不是每 Tick 保存 | `M/tile/TileMETransferDrive.java:69–81,156–215,341–371,549–569`；NBT 规模取决于元件内容 |
| 原生 Handler 缓存 | `onReady`、插拔/恢复后；`getCellArray/getNativeCellHandler` 按需调用 | 命中时不遍历；失效时清类型表、扫描 64 槽、遍历注册类型到首次匹配，重建所有有效 Handler | `:466–536`；复杂度约 `O(T+64K+CT×构造成本)`，T 为注册类型数、K 为元件处理器查找成本、C≤64；存在 4.1 风险 |
| 元件内容 NBT | 每次原生实际数量变更 | 原生 CellInventory 的独立类型列表和数量字段；保存一次可能枚举至多 63 种，不随物品每个单位保存 | `AE/me/storage/CellInventory.java:173–318,330–381`；ThE 同类覆盖保存见第 7 节 |
| 源节点、供电、频道 | AE 代理生命周期；本机供电/频道事件更新；实际请求查询 active | 一个节点；事件比较 `wasActive`，变化才发结构事件；转运阶段仍会多次检查 active | `M/tile/TileMETransferDrive.java:83–143`；AE 全 Grid 自身每 Tick 能量/缓存工作另计 |
| 源网访问模式 | 服务端配置请求、NBT 加载 | READ/WRITE/READ_WRITE；修改现有最多 64 个外层 Handler baseAccess，不为此重新构造元件库存；发源网结构事件 | `:218–233,429–460`；READ 可见性变化影响原生网络清单 |
| 权限 | GUI/绑定操作即时复核；目标解析/可用性/图见证按需复核 | 不缓存永久允许结论；源网安全所有者或放置节点玩家 ID 用于目标 INJECT | `M/transfer/AETransferController.java:389–401`；GUI 另查 BUILD、世界编辑和距离 |
| 设备身份 UUID | 构造生成、NBT 加载；GUI/绑定使用 | 一个 placement 身份；恢复物品放置不继承旧身份或 proxy | `M/tile/TileMETransferDrive.java:79,245–256,392–420` |
| 目标注册表及别名/优先级 | 绑定/编辑/删除/加载 | 最多 64 条稳定 UUID→维度坐标/别名/int 优先级；`getTargets` 每次复制列表；按优先级稳定排序按需执行 | `M/transfer/TargetRegistry.java:72–169`；注册查重 O(B)，排序 O(B log B) |
| 8 组路由、指定集合 | 服务端配置和 NBT 迁移/加载 | 固定 8 组，每组最多 64 UUID；不可变集合快照；删除仅清对应引用；改配置令周期/图失效 | `TargetRegistry.java:29–68,106–129`；NBT v2 严格校验，v1 单 UUID 迁移 |
| 初始化注册/资源/配方 | FML 初始化；无持续 Tick | 块、Tile、Item、配方、GUI handler、网络 channel；没有额外机器/后台任务 | `M/CommonProxy.java:20–42`、`M/RecipeLoader.java`；不构成持续转运成本 |

### 3.2 调度、事务与拓扑状态

| 状态及用途 | 实际触发与精确频率 | 数量、缓存与失效 | 工作量和限制 |
| --- | --- | --- | --- |
| 周期时钟 | 每个合格服务端 Tile Tick 执行 `clock.tick()` | 默认 60×20=1200 Tick，范围 20～1728000 Tick；不持久化剩余 Tick；修改/stop/加载重置 | `TransferClock.java:6–30`；低 TPS 下不是固定墙钟秒；损坏元件隔离在时钟前 return |
| 周期启动 | due 且注册表非空，无现有 active 作业 | 捕获排序绑定、8 组路由、来源实际 Grid；不检查是否存在开启组 | `AETransferController.java:112–129`；绑定非空全关闭仍启动 |
| 接口解析、真实 Grid 去重视图 | 每次作业准备，最多 4 绑定/Tile Tick | 最多 64 绑定；运行时 Grid 引用不落盘，准备最多 16 个工作 Tick；每组首次使用候选列表时再按身份去重 | `RoutingCycle.java:83–155`；目标块、节点、active、同维度、权限均检查；不强制加载 |
| 槽位游标 | 作业准备完，每 Tick `openNextSlot` 最多一次 | 64 槽顺序处理；关闭/无在线目标的组只检查候选，不读元件 Handler；第 65 次入口收尾 | `AETransferController.java:191–218`；不每 Tick 无条件扫描全部元件内容 |
| 类型快照、候选游标 | 选中可转运元件时生成；后续消费 | 每次一个本机原生元件快照≤63 条目；另一次校验遍历；`nextType` 每 Tick 最多检查 4 条，遇首个正数即停止 | `:214–240`、`SourceCellAccess.java:29–40`；不查源网总库存 |
| 当前候选及批次 | 有内容且图可用时每 Tick 至多一次 step | 当前内容数量为 long；物品4096、流体64000mB、源质4096；为候选复制当前组至多64目标引用 | `RoutedTransfer.java:18–53`、`TransferStorageTypes.java:13–32` |
| 模拟/能量/提取/注入/回填 | 单批 step，同一主线程调用链 | 一次目标模拟；一次能量预扣；一次真实提取；有提取才一次真实注入；有余量才一次回填；原生内部可有更多模拟与监听器调用 | `TransferTransaction.java:49–129`；并非一次 tick 只有一个原生库存 API 调用 |
| 单批事务和证据 | 阶段转换、数量变化、异常 | 至多一批；阶段、身份、requested/held/delivered/返回量和异常摘要；checkpoint 调 `markDirty`，不会立即生成并刷盘完整 NBT | `TransferTransaction.java:39–144`、`RecoveryEvidence.java:17–48` |
| 已知余量恢复 | RECOVERABLE 且时钟 due，原槽/元件/Handler相同 | 按所配周期重试一次原元件；成功结束旧游标；重载记录一律 UNCERTAIN，不自动重试 | `AETransferController.java:94–106,244–285,617–631`；持续源网写入可能再次占用回填空间 |
| 图、拓扑见证 | 仅存在正数候选时构建；类型变化、配置/生命周期/见证变化重建 | 一次仅缓存一个类型的图；完成后每活动 Tick 增龄，达到20 Tick或不新鲜则重建；不持久化 Grid 身份 | `:678–808`；不是空闲时每20 Tick全网扫描 |
| 图构造预算 | 每有候选的 Tick 至多 `step(4)` | 最多128 Grid（含源）、128设备、512绑定、256不同边、1024有效工作步骤；另有触限检查 | `RouteGraph.java:16–20,70–163`；单位内可读64元件元数据及检查8×64路由选择；收尾至多 O(128+256) |
| 图执行前证明复核 | 完成图且仍在20活动Tick内时，每次 `prepareGraph` 按需调用 `isFresh` | 最多64根绑定+512正向绑定+128设备=704见证；不是4工作预算的一部分 | `RouteGraph.java:112,129,174–183`；会重查世界、Grid、频道/权限，不是704次纯字段读取 |
| 当前周期拓扑复核 | 处理候选前、路由step前后、目标模拟/实际注入前、控制器收尾 | 每次至多64个已解析接口绑定端点（包含同Grid的不同接口）；完整正常批次最多6次列表复核 | `RoutingCycle.java:119–128`、`RoutedTransfer.java:32,43`、控制器`:142,172,297`；不重新解析全世界定位点，但仍访问已缓存节点的世界状态 |
| 同步注入 guard | 每次目标模拟/实际注入的动态调用栈期间 | ThreadLocal 栈，finally pop，最外层 remove；`blocks` 按对象身份查活动来源，正常写入仅额外 guard 检查 | `TransferInjectionGuard.java:11–42`、Tile`:582–590`；不等于跨周期全局环检测 |
| 状态枚举/最近结果 | 控制器分支、图完成、拓扑变化 | 最多64 UUID→状态；周期结束不保留旧Grid；pending仅保留原元件/Handler以备回填 | 控制器`:436–458,519–528,678–681,779–787`；恢复状态字符串每Tick重建，有小额持续分配 |

### 3.3 GUI、协议与保存状态

| 状态及用途 | 触发频率 | 单次工作量/缓存 | 风险归属与证据 |
| --- | --- | --- | --- |
| Container 槽及会话 | 每位玩家打开 GUI | 固定64机器物理槽+36玩家槽；服务端不复制第二套机器库存；客户端64槽影子库存；会话UUID、设备UUID、页版本、点击序号 | `M/gui/ContainerTransferDrive.java:52–100,114–156` |
| 原生槽 delta | 正常每服务端玩家Tick调用detect；点击/拾取也可额外调用 | 遍历100槽，跳过56隐藏槽，比较44个ItemStack/NBT；变化才复制并发槽包；分页可强制发8槽 | `:208–247`；`MC/entity/player/EntityPlayerMP.java:237–247,596–599` |
| GUI 配置快照 | 首次或设备配置修订号变化重建 | 最多8×64选择UUID、64目标，稳定排序；普通元件数量变化不重建配置 | Container`:298–344` |
| GUI 动态快照 | 每10次detect调用、处理配置请求、部分过期点击强制同步 | 深复制完整配置，检查8组、至多64目标状态及UUID；equals后变化/force才发包 | Container`:245–295,346–386`；正常无额外交互约10Tick，不是严格固定10世界Tick |
| 服务端 GUI 请求队列 | Netty 解码后入队；ServerTick START消耗 | 全局1024任务，最多128/Tick；每Container每世界Tick最多4配置/64点击 | `DriveGuiNetwork.java:27,64–77,192–234`，Container`:250–295`；解码/入队也有成本 |
| 请求数据 | 每请求 | Action最多342字节、文本256 UTF-8字节/数字32字节；Click56字节；无客户端库存NBT；State读前压缩负载≤32769字节 | Network`:86–189,260–280`；有会话/窗口/版本/距离/BUILD等复核 |
| 客户端状态队列/UI | ClientTick START消费、GuiScreen.updateScreen；tooltip每渲染帧 | 队列256；目标选择及勾选扫描有界；选中列表tooltip可做64×64字符串比较后才截短显示 | `M/ClientProxy.java:20,40–54`、`M/client/GuiTransferDrive.java:142–214,487–516`；主要是客户端成本 |
| 工具临时源引用 | Shift+右键事件；NBT读写/tooltip按需 | 源定位+UUID；绑定至多检查64既有目标及其已加载Grid；无Tick维护 | `BindingToolLogic.java:118–206`、`ItemNetworkBindingTool.java:44–105,134–209` |
| 世界NBT及隔离证据 | 世界加载、保存、卸载相关保存、恢复物品放置 | 写64槽+注册表/路由/当前事务，或复制原始坏NBT；不每Tick写盘；剩余时钟/游标/图不恢复 | Tile`:341–365`、控制器`:596–674`；外层库存加载有至多4096次合法键比较 |
| 元件保存回调/脏标记 | 每次原生元件保存及内部sourceChanged、事务阶段、配置变化 | 标记区块dirty，不触发64槽Handler重建，也不等于即时序列化/磁盘fsync | Tile`:482–485`、控制器`:78,310–326` |
| 卸载/失效/拆除 | 生命周期事件 | suspend清周期/图；正常拆除散落元件，pending时一个整机证据物品；新放置身份重生 | Tile`:132–143,380–420`，`M/block/BlockMETransferDrive.java:62–73,95–115`；不是固定频率 |

### 3.4 文字调用流程

```text
元件插入/取出
  Tile/Container → ProtectedCellInventory → AppEngInternalInventory
  → onChangeInventory(真实slot)
  → handlersCached=false → updateHandlers[最多64槽×注册类型]
  → topologyChanged → 源Grid.postEvent(CellArrayUpdate)
  → 原生Grid网络Handler失效 / monitor forceUpdate
  → 活动且READ可见时 Platform.postChanges[被换元件的类型清单]
  → AppEngInternalInventory.markDirty → onChangeInventory(-1)
  → 本机仅再次handlersCached=false【4.1重点风险】

普通源网写入/提取
  调用设备/玩家的 poweredInsert/poweredExtraction（是否计费由调用者决定）
  → 源网 NetworkMonitor → NetworkInventoryHandler[优先级/安全]
  → DriveCellHandler[源网模式/同步guard] → 原生cell Handler/CellInventory
  → 元件NBT更新 → ISaveProvider.saveChanges[区块dirty]
  → 原生monitor数量/监听通知
  （不查询绑定、组路由或目标网；通常不重建64 Handler）

转运作业
  每世界Tick → tickTransfers → controller.tick → clock.tick
  → 到期且有绑定 → RoutingCycle快照/排序 → 每Tick解析≤4接口
  → 每Tick推进≤1槽 → 该组候选为空则跳过
  → 本机原生Handler类型/能力检查 → 快照≤63条目/数量校验
  → nextType找到正数 → 当前类型RouteGraph构图step(4)/证明复核
  → RoutedTransfer.step[一个目标、一笔既有事务]
  → 目标monitor SIMULATE → 源网能量MODULATE预扣
  → 原cell MODULATE提取 → 原生NBT更新 + 源网delta
  → 目标Grid.getMEMonitor(type).injectItems(MODULATE)
  → 原生目标优先级/过滤/总线Handler → 目标monitor通知
  → 余量原cell回填 + 源网反向delta
  → IDLE才扣剩余并继续当前/下一目标；pending则停止游标/隔离
  → 条目耗尽推进下一槽；64槽结束释放周期/图
  → 世界后续保存才序列化当前NBT，磁盘提交由世界存档系统完成
```

## 4. 完整转运流程、原子性及数据安全

### 4.1 首要发现：条件性 P0，源 Grid 与本机可能使用分叉的原生元件缓存

**触发条件与完整证据链：**

1. 已就绪、接入活动源网的本机发生元件插拔。`AE/tile/inventory/AppEngInternalInventory.java:113–138` 在设置槽位后，先调用宿主 `onChangeInventory(slot)`，最后调用自身 `markDirty()`。
2. 第一次回调进入 `M/tile/TileMETransferDrive.java:190–211`，使缓存失效、构造 Handler 集合 A、同步发布 CellArrayUpdate，且在 READ 可见时发布元件库存变化。
3. `AE/me/Grid.java:194–198 → AE/me/NetworkEventBus.java:75–100` 同步分发事件。`GridStorageCache.cellUpdate():237–273` 清网络 Handler 缓存并同步 `NetworkMonitor.forceUpdate():352–365` 通知监听者。
4. 同步监听者可查询网络；另外原生 `Platform.postChanges():1408–1437 → NetworkMonitor.postChange():320–325` 在存在对应 ItemWatcher 时会**同步**调用 `getHandler().getAvailableItem()`。于是 `GridStorageCache.buildNetworkStorage():286–295` 可在槽位回调尚未返回时取得 A。这不是假设一个后台线程竞争，而是原生可达的同步查询窗口。
5. 随后 AppEngInternalInventory 的 `markDirty():156–160` 再回调宿主，`slot=-1`。本机 `onChangeInventory():185–187` 将 `handlersCached=false` 后直接返回，**没有再次让 Grid 失效**。
6. 下次本机 `getNativeCellHandler():368–371 → updateHandlers():489–536` 构造 B；Grid 仍可能使用 A。
7. `AE/me/storage/CellInventory.getCell():163–168` 每次创建新库存。构造器 `:94–102` 持有同一元件 NBT，却创建并加载独立的 `cellStacks`、storedCount 等。不存在由 CellRegistry 自动统一这两份数量缓存的机制。

**可推导的复制例子：** 同一元件的 A/B 起初均为 100。B 内部转出 10，源元件 NBT 为 90、目标增加 10；A 仍认为有 100。源网再从 A 提取 1，返回给玩家并将自身 99 写回同一元件 NBT。此时持久状态可成为源 99 + 目标 10 + 玩家 1 = 110。所有原生操作可以正常返回合法数量，事务的 `checked` 无法发现这 10 的复制。反向交替写入也可覆盖更新并丢失内容。

**影响范围：** 物品/流体的原生 CellInventory，以及继承它并以自身类型表整表保存的 `TE/inventory/EssentiaCellInventory.java:17,41–75`。一次重建处理全部槽，因此不应只检查新插入元件。具体哪些类型/槽受到分叉，取决于在第二次回调前哪些网络 Handler 已被重建并保留。

**与原生 Drive 的差别：** `AE/tile/storage/TileDrive.onChangeInventory():322–337` 对 `slot=-1` 也会按缓存状态重建、发布 CellArrayUpdate；本机的“只失效然后返回”行为不同。并非所有重复回调都必然产生分叉；无同步网络查询时，可能仅体现重复构造成本。

**结论：** [推导，P0] 这是具有完整源码触发链的条件性数据风险，不是已完成游戏复现。现有纯逻辑事务测试没有覆盖这一 AE 事件/宿主库存回调/网络缓存组合。后续应优先用带 ItemWatcher 的真实源网验证 Handler 对象身份和数量，再设计最小修复及集成回归；本轮未修复。

### 4.2 在一致 Handler 前提下，单批事务实际做了什么

以下推导额外要求：源 Handler 与真实元件状态一致；SIMULATE 无副作用；库存遵守返回值契约；接收端不虚空；来源与接收端没有未识别的底层库存别名。4.1 未排除前，这些不能被默认当作整机已经满足的事实。

设批次上限 `L`，模拟接受 `s`，能量授权 `a`，真实提取 `x`，目标承认接收 `e`，回填 `r`，已知未回填量 `h`：

```text
0 ≤ s ≤ L
0 ≤ a ≤ s
0 ≤ x ≤ a
0 ≤ e ≤ x
0 ≤ r ≤ x - e
h = x - e - r
局部数量等式：x = e + r + h
源数量变化：-x + r；目标承认量：+e
```

`TransferTransaction.move():49–90` 在真实调用后检查返回量；`GridStorageDestination.checkedQuantity():59–68` 还检查类型身份、`isSameType`、非负及不超过提供量。对源质，这比较的是原生方面身份而不是物品/流体表示。`RECOVERABLE` 的 h 是有证据的已知余量；`UNCERTAIN` 的 held 等字段只是调用前后留下的记录，不能继续套用“这个数就是可退款库存”的等式。

**顺序与通知：** 目标模拟 → 实际源网能源预扣 → EXTRACTING checkpoint → 原元件提取/原生 NBT 更新 → 源网负 delta → HELD/INSERTING checkpoint → 目标标准 monitor 实际注入/通知 → 按真实返回量更新 e/h → RETURNING checkpoint → 原元件余量回填/正 delta → IDLE checkpoint。checkpoint 仅为 `tile::markDirty`（控制器`:78`），不代表该阶段已经被序列化，更不代表刷盘完成。

### 4.3 三类存储的差异

| 类型 | 身份与数量 | 来源/目标/事务 | 特有边界 |
| --- | --- | --- | --- |
| 物品 | 原生 item stack，身份含原生 item/meta/NBT；long数量；批次4096 | 本机原生cell→泛型Grid monitor；相同事务 | 高复杂度物品NBT影响比较、保存和恢复物品大小；不得把复制元件NBT当转移 |
| 流体 | 原生 fluid stack，mB，long数量；批次64000mB；每计费单位1000mB | 同一泛型路径，保留流体总线/过滤器 | 小批次有能量向上取整；第三方缸的容量/虚空语义由原生handler决定 |
| 源质 | 注册 `essentia` 类型；方面身份；单位1；long数量；批次4096 | `EssentiaCellInventoryHandler`/`CellInventory` 子类→同类型monitor；不转成物品或流体 | `AEEssentiaStack.writeToNBT():160–168` 保存 AspectTag/Cnt；未知方面与元件内部坏NBT可能先被上游加载逻辑跳过/重算 |

`SourceCellAccess.java:14–40` 检查 ICellInventoryHandler、CellInventory 继承、非 overflow、类型数0～63及非负总数；快照再检查类型/负数/条目数/long合计溢出。这是实际能力门槛，**不是任意子类重写方法诚实或安全回填的证明**。目标不按设备类别封禁，仍允许原生源质总线等外部存储。

恢复记录的 payload 通常只是数量为 **1** 的类型身份（控制器`:149–152`），批次数量在 requested/held/delivered/reportedAmount。`payload.Cnt=1` 不能被解释为退款数量；`RecoveryEvidence.snapshot():17–48` 没有创建普通隐藏库存。

### 4.4 正常、失败和异常场景矩阵

表中“A/B/C/D”分别指：A 正常 API 数量关系、B 已知失败回填、C 未知结果隔离、D 磁盘/崩溃一致性。除特有单位外，三类采用相同控制流。

| 场景 | 源码中的实际行为 | 分类及不能承诺的事项 |
| --- | --- | --- |
| 正常完整接收 | x=e，h=0，清事务；路由只扣真实e | A：在4.2前提下守恒；4.1可使全局守恒前提失效；D仍无原子提交 |
| 模拟容量为0/过滤拒绝 | 没有源提取；reserveEnergy(0)不扣；可尝试下一候选 | A/B：原内容留源；模拟自身抛异常则停止本周期 |
| 模拟只接收一部分 | 只授权这部分；成功后剩余再按路由处理 | A：不是把整批分别发给各目标；小批取整及再次预扣另计 |
| 模拟允许、实际容量变小或清零 | 实际e可能小于x；余量x-e回填 | B：回填成功则IDLE后才可切换；失败留下h；已付能量不退 |
| 源真实提取少于请求 | 检查0≤x≤a，只将真实x注入目标 | A：不凭请求量生成库存；少提取/能量不足本身不将高优先目标误判为满仓 |
| 源提取0 | 清事务，没有目标实际注入；结束该内容游标 | A：没有物品转移，但预扣能源可能浪费 |
| 实际接收少于模拟/提取 | 用目标余量计算真实e，不用模拟值冒充成功 | A/B：已知剩余先回填；实际e从路由remaining精确扣除 |
| 部分成功后切下一网络 | 仅在事务不pending、拓扑仍有效、已知余量已回填后next++ | A：只处理未完成余量，同Grid候选去重；失败后不广播或重新发已完成量 |
| 已知余量只回填部分/完全失败 | 保存h，进入RECOVERABLE；原元件仍在才按周期重试 | B：同次运行可恢复；可长期锁定；重载后不自动重试 |
| 提取/插入/回填返回负数、超量、错类型/方面 | 抛异常；真实执行阶段转UNCERTAIN，保留可取得的返回NBT与诊断 | C：防止盲目回放；无法撤销第三方已做的非法修改，也不保证其返回证据能完整序列化 |
| Handler或通知监听者抛异常 | 真实阶段try内隔离；模拟/能量在事务try外，由controller.stop捕获 | C：提取后、返回前异常时held/reportedAmount可能仍0，不能证明没有提取；上游深度状态另见8.2 |
| 目标断电/频道或权限撤销 | 目标可用性在模拟和实际注入前复核；无效返回0接收 | B：提取后才失效则回填；第三方在注入中途失效/抛异常归C |
| 网络合并/拆分/目标卸载 | sourceGrid及已解析节点身份变化停止旧周期；新周期重解析 | B/C：未提取时安全跳过；提取后的拒收回填；不会强制加载；瞬时拓扑/隐藏路径无完整证明 |
| 两Tick之间源元件更换 | sameSource检查Tile、槽、ItemStack及Handler引用；结束旧槽游标 | A：正常完成的旧批次不自动重放；新元件等后续周期；pending禁止合法拔插 |
| 批内源设备拆除/元件强制替换 | 普通玩家请求不能在同一同步调用中间插入；重入第三方回调仍可能触发生命周期 | C：后续sameSource失败可隔离；已打包快照可能是中间阶段，不能宣称任意强删Tile都能完整返还 |
| 服务器正常退出 | 普通主线程调用完成后保存世界；未完成恢复状态随NBT保存 | B/C：已存在的RECOVERABLE重载也变UNCERTAIN；不承诺自动还回h；没有证据称正常退出等于跨网事务提交 |
| 关键步骤间硬崩溃 | dirty、内存held、目标变化可能未形成一致磁盘快照 | D：详见下一表，可能丢失、复制、证据滞后或无证据 |
| 接收端虚空/溢出销毁 | 服从AE原生“已接收”返回值，不退款或转给下一目标 | A：承认量e不等于仍存储量；允许玩家配置销毁，不能保证无损毁 |
| 多机转运环 | 同Grid拒绝、同步guard、有界按类型配置图阻断已确认返回来源路径 | A：减少可确认的循环；未遍历第三方隐含底层库存，也不保证动态拓扑瞬时完整 |
| 异常记录重载 | 原始transferRecovery复制保留，quarantine(null,UNCERTAIN,0,0,0) | C：不解释记录自动生成退款；避免由重放策略新增复制，但实际已丢内容或长期隔离仍可能存在 |

对应代码：`M/transfer/TransferTransaction.java:49–144`、`RoutedTransfer.java:28–58`、控制器`:94–106,244–348,410–449,596–631`、Tile`:132–143,380–420,549–569`。

### 4.5 中断位置和硬崩溃窗口

下面先描述逻辑中断点，再描述可能落盘组合。**不意味着每个checkpoint实际触发过一次存档**，也不意味着正常主线程存档会任意插进同步方法；跨区块快照与磁盘写入的完成顺序没有本Mod的共同提交约束。

| 中断位置 | 内存/证据风险 | 可能后果与已有保护 |
| --- | --- | --- |
| 模拟后、提取前 | 可能已付能量，库存尚未操作 | 不应有本机引起的库存转移；能量无退款；SIMULATE不诚实的第三方不在此保证内 |
| 原生提取已执行、source.extract尚未返回 | 原生NBT已变，但事务held/reportedAmount尚未赋值 | 运行时异常进入UNCERTAIN；缺少准确提取证据，可能长期隔离或实际损失，绝不能按held=0自动解锁 |
| HELD、尚未注入 | 内容已离源，只存在一批身份/数量在途状态 | 内存丢失且记录未持久化可能丢失；有旧记录也不能可靠断言目标从未接受 |
| 目标已改库存、尚未返回 | e未知，held仍可能为提取全量 | 自动回填全量可能复制，因此当前隔离；目标实际接受量需人工对账 |
| 回填已经改源、尚未返回 | 记录中的h可能大于真实尚未回填量 | 当前UNCERTAIN禁止再次回填，避免重复返还；仍可能证据不完整 |
| 事务已清为IDLE、源/目标待存档 | 当前NBT不再保留事务记录，两侧各自dirty | 源新/目标旧落盘可丢失；源旧/目标新可复制；都新通常一致；都旧通常回到旧库存，但外部玩家/第三方保存仍需一起考虑 |
| 已知RECOVERABLE保存后重载 | 快照不能证明另一Grid对应时点的状态 | 原记录保留、人工核对，不自动重放；代价是确定余量也可能长期不可用 |

### 4.6 原子性、幂等性与 IO Port 安全比较

| 性质 | 本 Mod 结论 | 与原生 IO Port 对照 |
| --- | --- | --- |
| 单次原生API操作原子性 | 对诚实原生handler可按同步返回理解一次修改；不能概括保证异常中无副作用 | IO Port同样信任原生/第三方API，均没有撤销接口 |
| 跨源/目标整体原子性 | **不具备**；中间状态对回调/监听者可见 | IO Port也不具备；本机跨独立Grid增加两侧独立存档的暴露面 |
| 正常数量一致性 | 仅在4.2前提下由实际量记账；4.1是当前重要反例路径 | 一般正常API路径等价；本机增加了更严格返回检查，但也新增缓存分叉风险 |
| 已知失败可恢复性 | 同次运行RECOVERABLE可重试原元件；不保证最终恢复 | IO Port `transferContents():544–546` 忽略回填余量，本机对此更保守、诊断更充分 |
| 未知结果处理 | 隔离，不自动补偿/重放；可能损失可用性或已有内容 | IO Port无相应持久隔离和审计证据；本机在防盲目重放方面更安全 |
| 幂等性 | pending/isolated游标和GUI会话版本阻止部分重复执行；**不存在全局事务ID/目标去重提交协议**，不能称跨崩溃幂等 | IO Port也没有跨库存提交幂等性；周期性重新读真实元件不是跨网幂等协议 |
| 防复制 | 防止若干重复发送/不确定退款策略；不覆盖4.1、硬崩溃、恶意handler、外部库存别名 | 本机不能因多了事务类就宣称总体一定优于IO Port |
| 防销毁 | 回填失败保留证据优于忽略余量；不保证物理库存始终有一份可访问内容 | 两者都允许原生虚空，都不能强制第三方保存；本机隔离可能比IO Port更久 |
| 保存意图 | 原生source.saveChanges与手工delta明确标脏宿主，但不是磁盘事务 | IO Port `getInv():488–501` 传ISaveProvider=null，元件NBT仍会改变，移动/世界保存时序列化；不能据此直接认定正常停机丢失 |

恢复锁只阻止元件插拔、配置和进一步转运；`getCellArray():466–474` 与 `DriveCellHandler:582–590` **没有因pending而冻结源网正常读写**。这符合现有存储服务设计，但现场库存可继续改变、占满回填空间，人工对账不能假设机器库存从异常时起静止。

## 5. AE 能量计算公式与场景

### 5.1 参数及持续耗电

设 `M = PowerMultiplier.CONFIG.multiplier`。固定AE2源码的 `AE/core/AEConfig.java:178–179` 从 `PowerRatios.UsageMultiplier` 读入该值，默认1、下限0.01；本次没有最终整合包实际配置证据，因此下列 `M=1` 数字只用于说明默认情形。

设 `d_i` 为第i个元件实际 `cellHandler.cellIdleDrain()` 返回值，单位为倍率前AE/t。正常接入、缓存已建立时：

```text
本机设备持续需求：          M × (2 + Σd_i) AE/t
k台原生Drive设备持续需求： M × (2k + Σd_i) AE/t
IO Port设备持续需求：      M × 1 AE/t（默认代理idle，无Drive式临时元件idle相加）
```

依据：Tile `updateHandlers():499–535`；`AE/tile/storage/TileDrive.updateState():351–382`；`AE/me/helpers/AENetworkProxy.java:60` 默认 idleDraw=1，IO Port 构造未覆盖为2。普通AE 1k/4k/16k/64k元件的源码idle分别为0.5/1/1.5/2，见 `AE/items/AEBaseCell.java:77–95,287–288`；不据此猜测其它扩展或本机实际安装元件的耗电。

`AE/me/cache/EnergyGridCache.java:173–176,208–209` 每Grid Tick扣持续设备需求和频道功耗；**不是只在搬运时耗电**。`AE/me/cache/PathGridCache.java:94–115` 的频道功率为 `channelsByBlocks/128.0`：无控制器时该量按节点数×所用频道数计算，有控制器时来自寻路计算。它依赖网络节点/布线的频道占用，不是Minecraft区块数，不能换成每台机器固定一个数。断电时只能扣到可供能量，公式表达正常供电下的需求，不能把需求写成始终实际扣足。

### 5.2 普通源网注入/提取

`CellInventory`、`MEInventoryHandler`、`NetworkInventoryHandler`、`NetworkMonitor` 的普通库存接口**不统一隐式扣一次搬运能量**。调用者若使用 `Platform.poweredInsert/poweredExtraction`，才通过传入能源源扣费；不同玩家终端/总线/机器还可能有自身耗电。故不能给“所有普通源网写入”指定同一个机器内附加费用，也不能断言源网普通存取完全免费。

本机普通存储路径没有调用自身 `reserveEnergy`；该方法仅供定时转运。`DriveCellHandler` 的外层访问模式不是能量收费器。

### 5.3 定时转运的预扣公式

令 `u = type.getAmountPerUnit()`，物品1、流体1000mB、源质1。目标模拟允许 `s` 原生数量后，`M/transfer/AETransferController.reserveEnergy():329–345` 实际执行：

```text
q = floor((s + u - 1) / u) = ceil(s/u)   // 代码先作整数除法，后赋给double
p = sourceEnergy.extractAEPower(q, MODULATE, CONFIG)
a = min(s, floor(p × u))
```

`AE/me/cache/EnergyGridCache.extractAEPower():202–204` 从能源库存取 `M×q`、返回倍率前的已付量 `p`；满能源时 `p=q`。该方法检查返回p不是NaN/Infinity/负数。当前批次及已知u使 `s+u-1` 不接近long溢出；源质单位不是mB，不应套流体1000倍折算。

| 操作阶段 | 本机通用AE搬运费 | 其它可能费用 |
| --- | --- | --- |
| 目标SIMULATE | 不实扣，之后才按s预扣 | 第三方若模拟有副作用，属契约违反 |
| 能源授权 | **源AE网络**实际预扣；目标网不通过此方法支付 | PowerMultiplier.CONFIG影响实取 |
| 源元件真实提取 | 不再单独扣一份通用搬运费 | 原生cell正常数量/NBT操作 |
| 目标标准monitor注入 | 不再自动扣一份通用搬运费 | 外部handler自身实现可能有内部能量/机器消耗，不能统一保证无其它费用 |
| 回填原元件 | 无第二次通用搬运收费，也不退之前费用 | 保存/通知的CPU开销仍发生 |
| 下一目标处理余量 | 再模拟、再按新授权量预扣 | 同一未成功内容可多次付费 |

因此不存在“本机预扣后，AE标准monitor必定再统一扣一次”的双重通用收费；但预扣不退款和第三方自己的费用是另外两件事。

### 5.4 典型批次与失败算例

前提：单类型、满能源、模拟与实际全接收、无虚空、无第三方额外费用；不含持续idle/频道耗电。

| 批次 | u | 本机请求q | 实际能源 | 无升级IO Port同工作量 |
| --- | ---: | ---: | ---: | --- |
| 4096物品 | 1 | 4096 | `4096M AE`；M=1时4096AE | 通常至少16个256计费单位回调，总搬运费同为4096M |
| 64000mB流体 | 1000 | 64 | `64M AE`；M=1时64AE | 单次原始预算256000mB足够，总64M |
| 4096源质单位 | 1 | 4096 | `4096M AE`；M=1时4096AE | 原生泛型路径的u=1，通常至少16个无升级回调，总4096M |

IO Port预算见 `TileIOPort.java:366–415`：256基础单位乘类型u，速度升级再放大。实际执行次数会受库存条目、升级、能量和接收容量影响，上表不是精确墙钟耗时承诺。

**失败的计费差异：**

- 模拟s=0：本机不扣搬运能量。
- 模拟100物品、真实只提17：本机可已扣100M，实际仅转17；没有83M退款。
- 模拟100物品、真实接收30、回填70，下一目标接收70：本机共可扣 `(100+70)M=170M`，真实只交付100；IO Port在这两个正常成功接收调用中按30和70收费，总100M。
- 同样情形若为64000mB先接受30000、余34000再送：本机可扣 `(64+34)M=98M`，IO Port按实际分段为 `(30+34)M=64M`。
- 每个目标SIM成功但MOD全拒且回填成功：同一余量在最多64不同Grid上可重复预扣；最终仍留源元件。不会因为“没有成功”自动退款。
- 不足一个计费单位的实际可供能量也可能被部分实取，而 `floor(pu)` 得到0；本机不提取，能源仍不退。合法小流体批次每次向上取整，费用不是无限精确的每mB固定小数价。

原生 `AE/util/Platform.poweredInsert():1357–1405` 先模拟能量、实际注入后按**真实接受量** `ceil(e/u)`扣费，0接受不实扣；IO Port分别调用它的模拟和实际模式（`:527–546`）。本机选择提前实扣有防止无能源先发送的保守含义，但能源经济性不能称与IO Port在所有失败场景相同。

同工作量的**总能量**应为上述一次性搬运费，加整个执行时间内两侧网络设备/频道耗电。IO Port的1AE/t不能直接替代“源Drive+目标Drive+IO Port”的完整方案基线；本机周期等待的idle也必须算入。没有实测处理时长和实际配置，无法给出两套完整设施的总耗电胜负。

## 6. 关闭转运时，与1台/7台/8台原生Drive比较

### 6.1 必须拆开的三个成本层

| 成本项 | 本机1台 | 原生Drive 1台 | 原生Drive 7台 | 原生Drive 8台 |
| --- | --- | --- | --- | --- |
| 可容纳槽位 | 64 | 10，不能容纳64元件 | 70 | 80 |
| AE节点/频道需求 | 1 / 1 | 1 / 1 | 7 / 7 | 8 / 8 |
| ICellContainer/provider实例 | 1 | 1 | 7 | 8 |
| 相同64有效元件的Handler数 | 64（理想一致缓存状态） | 不适用 | 64 | 64 |
| 相同64元件设备idle需求 | `2+Σd` | 不适用 | `14+Σd` | `16+Σd` |
| 组是否增加节点/频道 | 否，只有8份路由元数据 | 不适用 | 每台机器独立节点 | 每台机器独立节点 |
| 显示轮询 | 无原生10灯轮询；世界Tick控制器一直存在 | 每15Tick检查10槽 | 每15Tick合计70槽 | 每15Tick合计80槽 |
| 一次自身结构重建 | 扫64槽，重建全部有效元件库存 | 扫10槽 | 单台变动通常仅重建那台10槽；Grid结构失效仍影响全网 | 同左 |
| 普通数量操作 | 使用该类型的原生Handler，附加同步guard；不查路由 | 同类原生库存路径 | 同64元件时Handler层规模相近，provider入口更多 | 同左 |
| 世界/对象基础负担 | 1个Tile/节点，加64槽和路由/事务/图状态 | 1个Tile/节点，加10槽 | 7个Tile/节点、70槽，元件总NBT相同 | 8个Tile/节点、80槽，元件总NBT相同 |

这里“1个频道”表示正常频道制网络中的一个设备需求；实际布线频道功耗不能直接由此相除。上述表也**不把4.1中的重复缓存算作可接受正常状态**。

如果64元件恰好全是idle=0.5的原生1k元件，倍率前设备持续需求分别为本机34、7台46、8台48 AE/t；这只是明确假设下的算例，不是当前存档实测。相同64元件下，本机减少的是设备基础项12/14 AE/t及节点数量，不会凭8组分组消除64元件本身的耗电和库存维护。

### 6.2 A～E情景的实际分支

以下比较假定区块已加载、机器ready、源网代理活动、无恢复/隔离且没有玩家打开GUI；否则会按第3节门控提前退出或增加GUI成本。目标是否在线在各行另作区分。

| 情景 | 周期之外 | 周期到期后的工作 | 与原生基线的判断 |
| --- | --- | --- | --- |
| A：0元件，8组关闭 | 每Tick控制器常量检查/时钟；有GUI另计 | **无绑定**则直接返回；**保留绑定**仍排序/解析B个绑定，并检查64槽索引，但关闭组使其不读取元件 | 节点/闲置设备基数接近1台空Drive；本机64槽数组和控制器存在，不等于零开销 |
| B：8元件，全部关闭 | 源网正常8 Handler服务；不因普通数量变化查询目标 | 无绑定与保留绑定的区别同A；关闭组没有内容枚举/构图 | 正常存储更接近1台装8元件Drive；自身结构重建扫描64槽而非10槽；非“8台Drive” |
| C：64元件，全部关闭 | 正常64原生Handler和元件NBT维护 | 保留绑定仍有周期解析/索引检查；没有源内容枚举和图 | 节点/频道基数接近1台，容量相关成本接近装同64元件的7/8台；插拔的本机重建范围更大 |
| D：64元件，路由配置但无可转运内容 | 周期间仍为轻量控制器，源网正常工作 | 有在线候选时逐元件建立/校验快照，空列表下个Tick结束；无正数候选就不构图；未知/不安全类型或无在线候选会更早跳过 | 在64元件的原生存储成本外增加周期扫描、对象分配和目标解析，不能称只维护时钟 |
| E：64元件，持续正常转运 | 作业可能跨越一个或多个周期长度 | D之外增加按类型图、见证/拓扑复核、模拟、真实事务、保存和双方通知 | 额外成本由实际数量、类型/目标规模、图及目标网决定；不能用节点减少推导MSPT必降 |

绑定准备每Tick最多4项。若B=64且所有组关闭，活跃作业仍需约 `16准备 + 64索引 + 1收尾 = 81次控制器Tick调用`；若64个可安全转运的元件全为空、候选在线，则约 `16 + 64×(打开快照+耗尽检查) + 1 = 145次调用`，另有缓存/世界状态变化可能增加或中断工作。这里是控制流推导的工作调用数，相邻首末调用之间差一个Tick，**不是实测耗时**。

时钟在作业活跃期间仍递减，到期信号不会排队；作业结束后等下一次到期。周期被配置/停止/加载重置，世界未加载不推进。不能把默认1200Tick解释为“每60墙钟秒必完成一次全部64槽搬运”，也不能假定连续作业总从完成后再等满60秒。

### 6.3 六个直接问题的回答

1. **未配置转运是否还有周期扫描？** 从未绑定目标时没有64槽转运扫描；若只是把8组关闭而保留目标，仍有周期目标解析和64槽索引检查。
2. **空闲是否只维护计时器？** 无绑定、无恢复/隔离、无GUI、没有结构事件时，主要是计时器及几个常量门控；不能忽略底层AE节点、网络缓存、原生存储服务。不同空闲分支不能统称只剩一个减法。
3. **空闲仍拓扑检测/遍历绑定吗？** 无绑定不遍历；有绑定全关闭仍到期解析。无真实正数候选不构建跨周期图，也不周期执行其704见证证明。
4. **64元件是否分别注册原生Handler？** 是，每个被实际cellHandler识别的元件以其类型独立注册，不能把设备当作一个合并64元件库存；不被识别的元件不产生Handler。
5. **8组是否直接增加AE开销？** 不增加节点、频道或provider；增加本地路由配置、候选筛选和相应GUI/NBT元数据。
6. **同64元件对7～8台Drive哪些更低/相同/更高？** 更低：节点/provider/设备idle基数/基础Tile对象数，无动态10灯轮询。相近：64元件原生库存、分区/数量、内容NBT和Handler链规模。更高或新增：每Tick控制器、路由/图/事务、64槽集中重建、转运双方通知和GUI快照。总CPU/内存胜负尚无法确认，需要真实剖析。

## 7. 最坏工作量与性能瓶颈

### 7.1 有界调度不等于有界执行时间

令 C≤64元件、S≤63条目/元件、B≤64绑定、V≤B个成功解析的接口绑定端点、R≤V实际候选Grid、H为目标网络该类型的底层Handler数、W为相关监听者/Watcher数。同Grid的不同接口仍分别计入V，只有接收候选按Grid去重为R。H、W及第三方单次调用耗时不由本Mod限制。

- 来源一次完整内容枚举上界为 `C×S=4032` 个槽内条目；不是全源Grid库存清单。每种内容可能尝试R目标，**一次有界目标尝试遍历**可达 `4032×64=258048` 次。数量大而同一目标连续成功时还需要更多批次，故258048**不是全部成功搬运调用的绝对上限**。
- 若有Q个正数原生数量的积压，成功批次数还依赖各类型 `ceil(Q_type/batch_type)`；不能仅凭类型数给完成时间上界。条目会因当时快照和源网并行变化在后续周期重新发现。
- 每Tick最多一笔事务，但标准目标注入可进行sticky、原生优先级pass1/pass2，最坏O(H) Handler尝试，内部还有模拟提取/过滤等；外部总线可能再查容器、子网或缓存。见 `AE/me/storage/NetworkInventoryHandler.java:113–252`。本Mod没有手动重写优先级算法，也没有手动逐个外部容器分配。
- 每个真实源变更会让原生cell保存至多S种内容的NBT；有回填时再保存一次。目标若分配到多个cell，每个被修改cell也可能序列化自己的类型表。单批上限限制数量，不能限制复杂物品NBT、目标Handler遍历或磁盘队列耗时。
- **本机不主动扫描整源网，不等于整条通知链绝不查询整源网。** `NetworkMonitor.postChange():320–325` 的Watcher数量查询会进入 `NetworkInventoryHandler.getAvailableItem():455–488`：无跨网外部库存时可遍历该类型全部Handler；原生 `CellInventory.getAvailableItem():421–433` 又逐个检查元件条目。全原生情况下可达O(H×S)；发现外部网络库存时则转入带过滤的getAvailableItems。监听者读取失效的getStorageList也可引发全网清单重建。这些是AE原生通知的派生成本，原生Drive也承受；本机内部提取/回填增加了相应触发机会。IO Port的临时来源元件不作为Drive注册，因此没有相同的源provider delta负担。

### 7.2 图和反复见证复核

图构造的4个工作单位是“最多消费一个设备或接口绑定”的调度约束，不是4次常量读取：一个设备候选生成可读64个Handler元数据，并检查8组×64绑定的路由关系；根候选收集本身也在step预算外，最多检查8×64个缓存候选位置及至多64个不同根的可用性。图完成的反向可达性收尾一次性做至多128+256规模的元数据工作。

已完成图每次使用前的 `isFresh()` 最坏704见证不分Tick；一个正常完整批次的 `cycle.isTopologyValid()` 最多六遍V个已解析绑定端点（控制器前后、RoutedTransfer前后、模拟/实际注入supplier），合计最多6V≤384次节点位置检查，外加当前目标可用性。即使64个接口属于同一Grid、R=1，V仍可为64。它们与图证明不是相同缓存，也不是“目标每Tick只解析4次”的同一预算。

控制器只缓存**一个当前类型图**，相邻元件类型交替时会重新构图。它避免了三套长期Grid缓存，但物品/流体/源质交错的64槽可以增加重建次数；同类型连续元件也会在20活动Tick证明寿命到期时重新构建。没有内容时不会为三类各造一张空图。

### 7.3 多设备放大与错峰

- 每台设备自己有4图单位/1事务预算；D台设备可在同一服务器Tick合计4D单位和D笔事务，**没有服务器级总预算**。
- 多台设备的图可能反复访问相同Grid和其他转运设备；在尚未触及每图128设备上限的密集互联场景，整批设备的拓扑元数据读取可能接近 `D×可见设备数`，不能假定所有成本都严格线性。
- `TransferClock` 构造/重置都是同样的秒数×20，没有UUID偏移或随机错峰。同批加载/重置设备可能同Tick到期，产生周期尖峰。这是源码可见的同步条件，真实峰值需测。
- 稳定缓存命中时，普通存储并不查询远端路由；大量频繁插拔/合并拆分会反复引发源Grid结构失效、64槽重建和图丢弃。它们的代价与“静态满64槽但不转运”不是同一场景。

### 7.4 分配、GUI与保存放大

| 成本 | 确定行为 | 不能直接推断的结论 |
| --- | --- | --- |
| 逐条目对象 | 候选/身份stack copy、至多64目标引用列表、路由游标；逐批Source/Destination/Reception/supplier、模拟和实际stack copy | 没有堆采样，不能给出字节/次或认定GC已成瓶颈 |
| 已知恢复或UNCERTAIN长期驻留 | 一批身份/数量及原元件引用；每Tick拼接恢复状态字符串 | 自身没有不断追加的事务历史列表，但恢复可能长期占用内存/锁 |
| 标脏/通知 | 完整成功通常经历EXTRACTING/HELD/INSERTING/HELD/IDLE等多次markDirty，源原生保存+手工sourceChanged也可能重复标脏 | 多次dirty通常被世界合并，不能把它计成多次同步磁盘写；监听者CPU仍可能重复发生 |
| GUI | 每玩家每Tick44次NBT比较，周期状态深复制；有变化才发包不等于没有比较/分配 | 访问过全部分页后每Container/客户端影子库存可保留64元件历史快照；不能称永远只有8槽NBT内存 |
| 换页 | 强制发8槽，未同时更新旧inventoryItemStacks，下一detect在旧值不同时可能再发相同槽 | 不是每次换页一定重复；无论是否重复，都应量测实际包数 |
| 大NBT | 原生源/目标cell每次操作可能完整枚举内容；GUI比较/复制；世界保存全部64槽 | 原生63类型上限不限制每个物品身份NBT字节数，因此不能给固定序列化CPU上界 |
| 结构回调 | 本机slot=-1额外失效使按需再重建；原生Drive可能发两次结构事件 | 4.1先是数据正确性问题，其次才是可优化的构造次数 |

### 7.5 AE自身的周期工作与统计差异

`AE/me/cache/GridStorageCache.onUpdateTick():128–140` 每Grid每Tick服务已注册类型的monitor；有变化标记时才发送相应存储事件，不等于每Tick枚举所有物品。容量统计另依 `networkBytesUpdateFrequency` 运行：`AE/core/AEConfig.java:68,260–264` 默认1.0秒，GridStorageCache`:119–120` 转成20；计数器初始即到期，之后因先比较再递增，在默认值下实际**首Tick执行、后续每21Tick执行**，而非严格20Tick。最终整合包可改配置。

`GridStorageCache.updateBytesInfo():477–515` 只处理 `TileDrive`、`TileChest`。本机是独立 `AENetworkInvTile`，不满足这两个 instanceof：**不计入该原生容量/槽颜色统计**，但普通provider库存和数量仍被注册。此处较少统计工作不能包装成无代价优化；依赖这一统计的网络工具/显示可能缺容量，需要游戏确认其用户可见范围。

## 8. 其他安全、兼容和性能风险

### 8.1 聚合恢复物品的NBT/网络大小（P1，条件风险）

pending拆除时，`TileMETransferDrive.getDrops():380–389` 和 `RecoveryEvidence.pack():71–75` 将64元件及事务证据装入普通机器ItemStack，没有压缩字节预算。`M/CommonProxy.java:24` 使用普通ItemBlock；`MC/item/Item.java:646–648` 默认分享item tag。

本地 `MC/network/PacketBuffer.java:94–96` 将压缩NBT长度转为signed short，`:105–115` 的负长度按null处理，解压使用2MiB tracker。故超过32767压缩字节的大恢复物品，存在掉落/背包/拾取同步异常的源码条件。**尚无法确认最终GTNH是否由mixin扩容，因此不是已证明的永久库存丢失。** 现有GUI配置快照预算测试不覆盖完整64元件的恢复物品；普通单元件原本也有大NBT限制，聚合64个扩大了暴露面。

### 8.2 外部Handler异常可污染AE2监视器状态（P1，上游条件风险）

固定AE2 `NetworkMonitor.injectItems():211–216`、`extractItems():120–125` 在递增localDepthSemaphore后调用Handler，缺少保证递减的finally；`postChange():301–345` 的静态GLOBAL_DEPTH栈同样缺少异常清理。外部Handler或监听者在中间抛异常，可使后续`:296–298` 因残留深度而跳过通知，静态栈还可能保留旧monitor/Grid引用。

`NetworkInventoryHandler.injectItems():113–252` 的潜入/退出逻辑也有此类上游异常边界。本机TransferInjectionGuard自己的finally可清理自身栈，却不能修复上述AE状态。事务UNCERTAIN避免自动重放是必要保护，**不意味着整个AE网络已经恢复一致**。原生IO Port同样承受该上游风险，可能需要重建网络/重载世界；应在隔离测试世界确认，不修改AE2本体。

### 8.3 损坏元件内部NBT与类型契约（P1/待测）

`M/tile/CellInventoryNbt.java:22–67` 严格验证的是外层64槽、ItemStack可解码及单元件数量，未解析每种原生cell的内部条目。AE `CellInventory.loadCellStacks():384–405` 会跳过部分无效条目并可能直接save修复；ThE `EssentiaCellInventory.loadCellStacks():79–95` 会跳过未知方面并重算et/ec。

这些上游行为早于本机SourceCellAccess快照检查，因此不能宣称“任何损坏cell内部数据都已原样隔离保留”。合法配置限幅不用于库存，但上游的坏存储修复仍可发生。来源继承CellInventory只是兼容能力门槛，不防恶意/错误子类重写；不应为了扩大来源支持而删除它，也不能据它宣称所有第三方源质元件都可安全回填。

### 8.4 GUI/请求队列与多玩家（P1/P2）

服务端检查窗口、会话、设备UUID/指针、距离、BUILD及编辑权限、配置版本、页版本/递增点击序号；操作主线程串行，固定物理槽ID，不接受客户端库存NBT。未发现由列表索引或旧页重映射直接创建第二份元件的路径。但普通槽没有显示内容版本锁，可能像原生Container一样拿到另一玩家刚放入该物理槽的**当前**元件；这需要交互测试，不能当作复制证据。

全局队列offer失败未反馈，限流发生在任务入队后，恶意流量可挤掉合法请求。有有效会话的过期点击可触发整Container及状态强制重同步，单Container最多64次/Tick，编码/带宽压力不能只按平时10Tick同步估计。客户端状态应用没有独立快照递增序号，正常同连接有序通道下未证明错误；窗口号复用、高延迟和旧队列残留仍应测试。出处：Network`:192–234`，Container`:267–295,393–397`，ClientProxy`:45–52`。

### 8.5 循环检测和长期引用（P1边界）

- 图只识别已加载、本Mod设备提供的、同类型安全来源元件所在组的配置候选路径；不读取所有目标设备、不识别所有共享底层容器。其它转运驱动器或存储总线的存在本身不封禁目标网。
- 新设备/原先不可见的新路径可能到下一次有界重建才被看见；构图跨Tick时也可能遇到拓扑变化。已完成的正向见证会复核，但目标实际注入前不是对整个图重新取得跨网锁，不能称瞬时全局循环检测。
- 配置候选图会保守阻止“可能返回来源”的目标，即使这批内容事实上不会进入对方驱动器；多目标可改走其余无环候选。预算触限会停止本周期，可能降低可用性，不能通过取消安全检查来追求速度。
- 正常结束/停止/卸载清图与cycle；lastTargetStatuses只存UUID/枚举，registry受64上限限制。自身没有发现无限增长的历史Grid身份缓存。RECOVERABLE保留一份原cell/Handler不是历史列表，但长期隔离会延长其生命周期；8.2上游异常静态栈另当别论。

### 8.6 流量统计的观测边界（P2）

原生 `NetworkMonitor.monitorDifference():268–271` 会调用 `ItemFlowGridCache.recordFlow`；本机内部来源delta直接走 `GridStorageCache.postAlterationOfStoredItems():317–319 → NetworkMonitor.postChange`，没有经过这次recordFlow调用。目标真实注入通常经过monitorDifference，拦截器则可能提前处理。库存变更通知与流量统计不是同一个接口；不能推断两侧原生流量显示必然对称。该差异本身不是库存丢失证据，最终工具如何呈现仍应实测。

## 9. 改进建议：仅供后续决策，本轮不实施

| 优先级 | 建议与收益 | 复杂度 | 新风险及验收要求 |
| --- | --- | --- | --- |
| **P0** | 优先复现4.1的双Handler分叉，明确一份原生元件库存实例如何同时供源网与内部转运使用；在任何缓存失效后保证Grid与本机视图一致 | 中，需真实AE事件/库存回调集成分析 | 不宜简单删某个通知或强行每次重建；可能造成幽灵库存、重复delta或权限可见性错误。先记录A/B对象身份，再用三类数量守恒集成测试确认修复 |
| **P0（保护原则）** | 继续保留未知结果隔离、禁止自动补发/重放；将跨Grid硬崩溃列为发布边界并制定备份/人工核对要求 | 说明与测试中等；真正原子协议复杂度高 | 不为“完整性”匆忙加入自动退款。若以后要求更强保证，需要目标幂等参与/协调存档等架构证据，超出本次优化范围 |
| P1 | 验证满64复杂NBT恢复物品的压缩长度、最终GTNH补丁和拾取/放置链；必要时另行设计有界、可审计的恢复承载方式 | 中至高 | 拆分物品或外置证据可能引入丢失/重复/关联失效，不能仅加截断丢数据 |
| P1 | 故障注入验证第三方异常后的原生monitor深度、通知恢复及隔离范围，完善人工对账证据 | 中至高 | 不反射重置AE私有栈，不在缺乏证据时自动解除隔离；可能需上游独立修复 |
| P1 | 对全组关闭但有绑定的空闲分支做实测，考虑后续增加“是否有有效启用路由”的内部快路径 | 低至中 | 必须保留恢复重试、模式变化和NBT迁移；不能因为跳过任务丢失必要生命周期事件 |
| P1 | 测量图704见证、6V复核、多设备同周期尖峰与交错类型重建；再评估错峰、失效事件或预算改进 | 中至高 | 降低复核/缓存更久可能漏拓扑/权限变化；共享图缓存易重新引入历史Grid身份问题，不能先优化后补安全 |
| P1 | 明确预扣不退款的玩法与能耗预期；若改计费，复用原生能源/注入语义并测试授权不足和异常 | 中 | 后扣可能先交付后无能量；退款存在重复退费窗口。不能以简单按delivered退款代替设计 |
| P1 | 实测原生容量工具对本机的统计缺失；评估合法兼容入口或明确显示边界 | 中，取决于上游API | 不为统计而伪装成多个Drive/更改真实provider；不要将减少统计当性能收益宣传 |
| P2 | GUI动态快照避免无变化深复制/重复UUID解析、换页槽包与客户端tooltip重复查找；以包计数和采样证明收益 | 低至中 | 可能出现过期配置/错误目标显示；必须保留服务端版本/权限，不扩大每Tick全量同步 |
| P2 | 队列饱和反馈/早期合法限流及过期请求重同步节流 | 中 | 不接受客户端预测库存或删服务端校验；必须避免限流导致卡光标或旧会话操作 |
| P2 | 采样nativecell保存与结构重建分配，评估是否有可合法合并的重复标脏/通知 | 中至高 | 暂缓保存或合并delta可削弱持久化/监听者一致性，先解决P0，不做大规模缓存重写 |

## 10. 待GTNH游戏验证清单

所有项目均**未在本次审计中执行**。使用独立测试世界和可恢复备份；不要在生产库存上注入崩溃或异常。

| 验证组 | 场景与需要收集的证据 |
| --- | --- |
| 首要缓存一致性 | 源网配置ItemWatcher/存储等级发信器等同步关注者；插入带已存内容元件；记录slot回调顺序、Grid与本机原生Handler身份；交替内部转运和源网存取，重载前后核对总量。物品/流体/源质分别做，覆盖换入/换出及多槽 |
| 运行时数量 | 完全接收、0容量、模拟接受实际部分/零、源网同时提取导致少提取、回填空间被源网填满；核对源+目标+玩家+已知held，未知结果单列，不凭记录生成补偿 |
| 事务异常 | 在模拟、能量、提取前后、实际注入前后、回填前后和通知监听者处注入异常；核对phase/uncertainOperation/failure/返回证据和AE后续通知是否仍工作 |
| 正常停机与重载 | 成功IDLE、已知RECOVERABLE、UNCERTAIN、封装后重放各自停机；记录恢复锁和源网仍可读写的实际状态；确认没有自动补发 |
| 硬崩溃 | 分离源/目标区块存档时点，在测试世界制造关键中断；比较两侧及玩家持久快照。目的为测清损失/复制窗口，不能以一次未复现证明原子性 |
| 三类与外部容器 | 原生元件及物品/流体/源质总线各自的兼容容器；分区、过滤、优先级、容量变化、虚空/溢出；核实原生承认量和实际保留量的区别 |
| 多目标/图 | 64绑定、同Grid多接口、8组多选、稳定同优先级、自动/指定；部分接收后余量故障切换；两/三台同类型环、不同类型交叉路径、图预算超限、拆分/合并/卸载/重连/权限撤销 |
| 来源权限 | READ/WRITE/READ_WRITE真实清单可见性、源网存取、WRITE仍可内部转运；pending期间源网读写与回填失败；检查状态显示不是交付证明 |
| 恢复物品 | 64元件×63复杂高熵身份NBT，测原始/压缩字节数；拆除、爆炸、保护Mod、snapshot回滚、掉落/拾取、背包同步、放置、重载；核实最终GTNH网络大小补丁 |
| GUI/协议 | 多人同机、8分页、Shift/数字键/拖放/双击、持续转运、长延迟/关GUI/拆机、BUILD撤销、旧包/窗口号复用、队列饱和及过期点击重同步压力 |
| 容量兼容 | 原生网络工具/相关界面容量、可用/总槽与颜色统计，对比实际可见和可写库存；定位缺失是否影响除显示外的功能 |
| 可选Mod边界 | 无ThE服务器启动、注册类型不存在；ThE1.7.64实际方面NBT和存档恢复；不要由字节码检查推断游戏加载已通过 |
| 基准A～E | 使用同64个元件、同内容/物品NBT、同布线、同监听器、同玩家GUI条件，比较1/7/8台原生Drive、本机无路由/保留关闭绑定/空内容/持续转运，IO Port另以同工作量及明确升级配置测 |
| 真实性能采集 | 记录实际M、IOPort TickRates、容量统计频率、TPS、分位MSPT、分配/GC、Handler构造数、CellArrayUpdate和delta数、目标调用/监听次数、图构造/见证次数、NBT字节和包数；同时记录吞吐及实际成功量，不能只看平均TPS |

### 最终审计结论

当前架构在节点/频道层面确实只有一台设备，并复用了AE原生存储与目标兼容路径；64元件与主动跨网转运的维护成本没有因此消失。现有事务对已知失败和不确定结果提供了有价值的保守保护，但不能覆盖跨存档崩溃，也不能补救原生元件缓存的分叉。**最应优先处理的是4.1的条件性P0复现与一致性修复，而不是先追求吞吐或减少安全检查。** 其余性能建议应结合上述实测逐项决定，本轮没有实施任何优化。


## 11. P0 专项验证与修复补充（2026-10-27）

本节是审计后的专项交付；第0～10节与4.1的原始发现原文保留，代表修复前基线。**当前状态：源码调用链成立，组件级回归已复现覆盖并修复；完整 GTNH Grid / ItemWatcher 场景尚未游戏验证。** 本轮没有调整定时、路由图、能源计费或事务架构，也没有尝试修补跨 Grid 硬崩溃原子性。

### 11.1 可达性与实际复现证据

- 固定 AE2 的 `AppEngInternalInventory.setInventorySlotContents():113–138` 顺序为改槽位、同步宿主槽位回调、`markDirty()`；`decrStackSize():80–97` 相同，`markDirty():156–160` 再调用宿主 `slot=-1`。
- `Grid.postEvent():194–198 → NetworkEventBus.postEvent():75–100` 为同步派发。`GridStorageCache.cellUpdate():237–273` 先清网络 Handler，再同步通知 monitor 监听者。监听者可以在宿主槽位回调返回前查询并缓存新 Handler。
- 更直接的原生路径是槽位事件结束后，同一宿主回调继续执行 `Platform.postChanges():1408–1437`；`NetworkMonitor.postChange():320–325` 在匹配 ItemWatcher 时同步 `getHandler().getAvailableItem()`。此时 `cellUpdate()` 的 monitor 锁已经在其 finally 中释放，不存在“锁住查询直到第二次库存回调后”的保证。
- `GridStorageCache.buildNetworkStorage():286–295` 保存 provider 当时提供的 Handler A。修复前宿主随后在负槽位回调中仅使内部缓存失效；内部转运下次取 Handler 时创建 B，而无第二次 Grid 失效。
- `CellInventory():65–128` 的独立 `storedCount`、`cellStacks` 共享同一元件 NBT；`saveChanges():330–381` 会把各自缓存整表写回。物品、流体及继承该类的 ThE 源质均受同类一致性前提约束。原生 `TileDrive.onChangeInventory():322–337` 则对负槽位回调也发布失效事件，不能用其行为推断本机旧实现安全。

**实际执行的修复前回归：** `CellHandlerCacheTest.alternatingNetworkAndInternalOperationsPreserveNativeCountsAndNbt()` 在真实 AE2 `CellInventory`、`CellInventoryHandler` 和 `NetworkInventoryHandler` 上执行“初始100 → 内部提取10 → 库存markDirty → 网络提取1”，JUnit 报告明确失败：`expected:<89> but was:<99>`。另有身份断言观察到已发布的 A 与 `getNativeCellHandler()` 返回的 B 不同。这里不是只用测试替身人为实现一个“会覆盖”的库存：计数更新、提取和 NBT 保存来自固定版本的真实 `CellInventory`。

**复现边界：** 为避开普通 JVM 中 AEStack → AEApi → Forge Loader 的引导限制，侧别探测、注册表查找、类型/stack/list 与 Grid 发布入口使用测试替身。测试宿主在第一次真实库存回调内部同步构建并保留网络 Handler，随后由真实 AE2 库存方法执行第二次负槽位回调；没有启动真实 Grid、真实 ItemWatcher 或真实物品/流体/源质元件注册。因而它证明回调组合及缓存覆盖机制，不等同于 GTNH 整合包复现。

### 11.2 最小修复与一致性边界

| 修改 | 当前源码位置 | 效果 |
| --- | --- | --- |
| 分开处理批量加载、负槽位保存及具体槽位变化 | `M/tile/TileMETransferDrive.java:184–224`，`onChangeInventory()` | 加载仍失效；`slot=-1` 仅经 `saveChanges(null)` 标记区块待保存，不改变 Handler 缓存；具体槽位回调保留原有重建、CellArrayUpdate 和库存 delta |
| 缓存完成标记移到闲置耗电事件之前 | 同文件 `updateHandlers():497–552`，尤其`:545–551` | `AENetworkProxy.setIdlePowerUsage():314–324` 可同步发事件；其监听者查询时已经获得完整、相同的缓存，不再递归构造第二套 |
| 对真正的构建中递归查询拒绝返回半成品 | 同方法的 `buildingHandlers` 检查及 finally | 原生构造期间的保存请求可以通过；若第三方工厂反向请求尚未构造完的库存，明确抛出异常，不返回部分 Handler 表；finally 释放构建标记 |
| 构建中禁止改动元件实体库存 | 同文件 `ProtectedCellInventory:581–603` | setter 在实际修改前拒绝，decrement 返回 null；原有事务/恢复插拔锁保留 |
| 隔离全局注册表的测试接点 | 同文件 `findCellHandler():554–559`、`cellStackTypes():561–563` | 包内方法的生产实现仍直接委托真实 AE2 注册表；无替代库存、运行配置或第三方容器分支 |

修复后的关系是：**同一结构世代中，Grid 持有的外层 Handler 与本机内部转运使用的原生 Handler 指向同一库存实例。** 真正插拔元件仍允许合法重建整个 Handler 集合，并同步使 Grid 的组合缓存失效；不会承诺对象身份跨插拔永久不变。源网访问模式仍只修改外层权限，不替换原生库存，内部转运继续绕过外层模式而遵守元件自身规则。

`Platform.postChanges()` 为计算插拔 delta 创建的临时枚举库存仍由上游保留；它不是长期注册或供本机转运使用的第二套可写缓存。内部提取/回填的数量 delta、目标标准 monitor、事务记录、回填及隔离代码均未改动。

**不扩大保证：** 工厂构建中重入读取会得到明确错误，不声称兼容任意第三方的递归构造逻辑；任意工厂/监听者抛异常仍受原审计的上游异常边界限制。外部 Mod 如果绕过库存 API 直接替换元件、原地改写元件内容 NBT 或配置且只发普通 markDirty，本轮没有建立其语义判别协议；正常插拔/配置结构变化应提供真实槽位通知。不能靠忽略不确定结果、重放事务或凭旧记录补偿来修复过去可能已经覆盖的数据。

### 11.3 自动验证与性能影响

- 新增 `src/test/java/com/kyou/metransferdrive/tile/CellHandlerCacheTest.java`，8项测试；保留全部228项既有测试。
- 覆盖：真实 setter/decrement 的 `slot → -1` 顺序；第一次回调内同步缓存；交替网络/内部存取与原生 NBT；拔出后剩余槽位；保存、重新实例化、重插；64槽/8组及 READ/WRITE/READ_WRITE；闲置事件重入；工厂递归拒绝；批量 NBT 读取。
- 三类合约替身分别进入相同的真实 AE2 `CellInventory` 基类算法；实际 `EssentiaCellInventory` 的特殊方面加载/保存仍由既有固定版本字节码核查和最终游戏测试覆盖，不能把本测试称为真实 ThE 元件验收。
- 测试只通过反射注入本 Mod 自有 Tile 的库存字段；不修改 AE2 私有字段或字节码。测试库存仅重写 `eventsEnabled()` 避开 Forge 侧别引导；实际 setter/decrement/markDirty 方法均未替换。
- 为加载实际 Tile 的 GTNHLib capability 父类，将项目已经使用的 `GTNHLib:0.11.52:dev` 同版本加入测试依赖；未改变运行版本、生产依赖或工具链。
- 已执行 `test --tests ...CellHandlerCacheTest spotlessApply`，以及完整 `build spotlessCheck checkstyleMain checkstyleTest --offline`（均使用项目 `.gradle-user-home`）。最终全部成功。
- 最终 JUnit XML：**236项，0失败、0错误、0跳过**。Checkstyle XML：主代码33文件、测试24文件，**0问题**。`SpotlessApply` 后核对未格式化无关已有文件。

**性能检查：** 普通保存回调、源网原生存取和内部提取/回填不再因 `slot=-1` 使64槽缓存失效。64槽/三种模式测试保存原生 Handler 身份并核对构造计数，模式切换、组路由修改及普通存取未增加构造。结构插拔仍是既有64槽重建；其源网 CellArrayUpdate 和插拔 delta 分支原样保留，负槽位分支不新增事件。测试中的发布计数是组件替身计数；真实 Grid 的事件数须游戏测量。新增构建标记仅在缓存未命中及元件变更入口检查，**未增加 Tick 回调、定期扫描或路由工作**。没有测量或宣称 TPS/MSPT 提升。

### 11.4 游戏复现与修复验收方案（未执行）

1. 在有电、有频道的源 Grid 安装本机，保持 READ_WRITE；使用终端和监听同一内容的存储监视器/发信器制造同步监听/ItemWatcher 查询。绑定独立目标 Grid，不连接双方网格。
2. 插入含100个同种物品的元件，使目标本轮恰好接收10；随后从源网提取1。清点应为源89、目标10、玩家1；反向插入及重复轮次也要守恒。测试后可关闭该组，避免下一个周期干扰清点。
3. 流体改用100000mB、转运10000mB、源网再提取1000mB，期望源89000mB；源质使用同一方面100、10、1，期望源89。分别测试正常接收和部分回填。
4. 使用开发调试器在 `getCellArray()` / `getNativeCellHandler()` 观察对象身份：同一结构世代，外层 `MEInventoryHandler.getInternal()` 必须等于内部转运原生 Handler。连续两次库存回调之间、闲置耗电事件内查询都不得产生 B。
5. 64槽跨8组混合安装三类元件，逐一插拔、快速移动、切 READ/WRITE/READ_WRITE；确认源网可见性/权限、内部转运、剩余元件及卸载/重载后数量。
6. 记录真实 Handler 构造、CellArrayUpdate、数量 delta 次数：普通内容变化无64槽重建；单次结构改变没有尾随 markDirty 导致的额外 CellArrayUpdate。供电/频道变化的独立合法事件应单独计数。
7. 正常保存退出并重载，再做交替存取；另行检查 ThE 原生方面 NBT、存储总线目标、外部虚空及不确定事务隔离。本轮没有验证或改变硬崩溃窗口、能源预扣、跨周期检测性能等其他原报告结论。

**专项交付结论：** 4.1 的风险没有被单元测试通过所否定，而是通过固定版本真实库存组件复现了具体覆盖结果，再以负槽位语义及同步缓存发布修复。当前可认定上述代码路径和组件回归已修复；真实整合包集成验收、任意第三方异常及历史受损数据仍不能据此宣称安全或自动恢复。
