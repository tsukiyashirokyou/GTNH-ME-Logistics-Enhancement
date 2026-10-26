# ME Transfer Drive 开发状态

> 更新日期：2026-10-26。本文件只记录当前阶段事实；完成下一阶段时替换过期内容。
> 完整需求和开发规则见 [AGENTS.md](AGENTS.md)、[CODEX_PROMPT.md](CODEX_PROMPT.md)。
> Starter 排错记录见 [环境配置归档](docs/archive/environment-setup.md)，仅在相关故障复现时查阅。

## 当前状态

- 目标为 GTNH 2.9.0-RC-2 / Minecraft 1.7.10 / Forge 10.13.4.1614 的独立 Mod。
- Starter、第一阶段基础方块与 TileEntity、第二阶段 64 槽源网存储代码均已完成。
- 第二阶段已通过完整 Gradle 构建、格式检查、Checkstyle 和纯逻辑单元测试。
- 本阶段不以游戏内测试为前置条件；第二阶段的实际游戏行为仍未验证。

## 已实现并通过自动验证

- `DriveSlotLayout` 固定 8 组、每组 8 槽、共 64 个物理槽；组内索引和全局槽号可互相映射。
- `DriveSlotLayoutTest` 的 3 项测试通过，覆盖全部槽位唯一映射及越界拒绝。
- `TileMETransferDrive` 编译后继承本地 AE2 `AENetworkInvTile`，实现 `ICellContainer`；`ICellProvider`、`ISaveProvider`、`IReshuffleAccess` 经接口继承满足。
- 64 槽 `AppEngInternalInventory`、AE2 原生元件 handler 查询和按注册存储类型提供 handler 的代码已编译。
- 源网访问模式使用本地 `AccessRestriction`，仅允许 READ、WRITE、READ_WRITE，默认 READ_WRITE；模式读写 NBT 的代码已编译。
- 方块拆除时取得并生成库存中元件掉落物的代码已编译；元件 `ItemStack` 的原有 NBT 随物品副本保留。
- 完整 `build` 成功；Spotless 通过，主源码和测试源码 Checkstyle 均为 0 个问题。
- 编译产物为 Java 8 字节码；本地 `javap` 确认 TileEntity 的 AE2 接口和方法签名。

## 已实现但尚未经过游戏内验证

- 元件插拔按结构性变化重建 handler 缓存、更新闲置耗电并通知 AE 存储网；普通元件内容写入走原生 handler，不触发 64 槽重建。
- `saveChanges()` 标记 TileEntity 所在区块待保存；库存插拔也标记待保存；库存与模式由 AE2 TileEntity 事件写入 NBT。
- `AENetworkInvTile` 提供网络代理的区块卸载、失效及重新验证生命周期；方块破坏路径尝试返还全部已安装元件。
- READ：源网可见并可提取，不可注入；WRITE：源网可注入，不可见且不可提取；READ_WRITE：两向允许。最终权限还受元件自身权限限制。
- 模式切换更新现有 `MEInventoryHandler.setBaseAccess()` 并通知网络刷新，不把 `RESHUFFLE_ACCESS` 当作源网模式；目前只有服务端代码入口，没有 GUI 控件。
- 物品、流体元件使用 AE2 已登记的原生 handler；其他已登记类型仅在相应扩展提供有效 handler 时被动加入源网。源质兼容性尚未确认，且不属于未来转运类型。
- 世界放置、频道连接、真实注入/提取、模式切换后的网络可见性、存档重载、拆除返还及第三方元件兼容性均未验证。

## 本地 AE2 API 依据

- 依赖及源码包为 `Applied-Energistics-2-Unofficial:rv3-beta-1080-GTNH`；GTNH 2.9.0-RC-2 固定清单列出该版本和 GTNHLib `0.11.52`。
- 本地 `TileDrive` 固定 10 槽；本项目复用其 `AENetworkInvTile`、`AppEngInternalInventory`、`ICellContainer` 与按槽注册 handler 的模式，不继承其固定数组或灯色同步。
- 本地 `AEStackTypeRegistry.getAllTypes()` 枚举注册类型；`ICellHandler.getCellInventory()` 返回元件本身的原生存储 handler。
- 本地 `MEInventoryHandler.setBaseAccess()` 将设备模式与元件权限取交集；`getAvailableItems()`、`canAccept()`、`extractItems()` 分别执行可见性及读写限制。
- 本地 `GridStorageCache` 订阅 `MENetworkCellArrayUpdate`；`TileDrive` 用 `Platform.postChanges()` 通知元件插拔；`saveChanges()` 标记区块待保存。
- 版本来源：[GTNH 2.9.0-RC-2 固定清单](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/blob/2.9.0-RC-2/README.md)。

## 构建环境与未解决问题

- Gradle Wrapper 9.7.1 使用 JDK 25；本地 Java 17 / 8 供构建工具链调用；缓存位于项目内 `.gradle-user-home`。
- 构建命令仍需一次性指定本机 Java 17 / 8 的 `org.gradle.java.installations.paths`；离线完整 `build` 已成功。
- 默认 Jabel 路径在当前环境对 `compileInjectedTagsJava` 报 `Cannot close compiler resources`；本项目源码未使用现代 Java 语法，已将 `enableModernJavaSyntax` 设为 `false`，常规构建通过。
- 暂无完整 GTNH 整合包的游戏内测试；行为、跨 Mod 兼容性和真实性能测试统一留待功能完成后。
- 正式发布版本号和 LICENSE 模板占位符仍待确认；未测量 TPS/MSPT，也未声称存储性能收益。

## 尚未实现

- 自动转运、目标网络定位与绑定、去重和优先级、8 组路由配置。
- 绑定工具、机器 GUI、访问模式的玩家操作界面、周期设置及工作台配方。
- 物品/流体跨网安全转运、掉线与崩溃恢复流程及真实性能基准。

## 下一阶段

- 按后续指令实现单目标定时转运，只处理本机元件中的物品和流体；源质不参与转运。
- 先核实本地 `TileIOPort` 的公开调用路径，设计目标模拟、实际提取/插入和失败回退，再加入分 Tick 预算。
- 继续以完整 `build`、静态 API 检查和适用自动化测试验收；游戏内行为统一标记未验证。
