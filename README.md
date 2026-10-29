# Kizuna Meridian Debug Addon

这是仅供管理员使用的调试库，模组 ID 为 `meridian_debug`。它读取已安装身体宿主的权威快照，不复制身体账本。

## 功能

- `meridian_debug:limb_lens`：手持时每 10 tick 显示准星实体的部位 HP、状态、总 HP、体力和真元；右键打印完整快照。
- `meridian_debug:aggro_link`：选择两个可攻击生物，持续覆盖其攻击目标；普通右键互相仇恨，潜行右键建立单向仇恨。
- `/meridian_debug damage watch on|off [targets]`：记录自己参与的双向伤害，包含来源、命中部位、原始与结算剂量和部位剩余 HP。部位伤害溢出时追加原始余量、来源系数和各接收部位的 HP 损失；全身无部位可承受的余量单独标注。日志读取同一事务的固定回执，即使图腾随后恢复血量也保留原始分配事实。
- `/meridian_debug heal <targets> [all|health|limbs|stamina|zhenyuan]`：按目标恢复；省略模式时执行 `all`。
- `/meridian_debug heal <targets> limb <部位> [数值]`：按 Human 身体的实际部位 ID 恢复。
- `/meridian_debug aggro <A> <B> [mutual|oneway]`：设置仇恨方向；`/meridian_debug aggro clear [targets]` 清除覆盖。

## 命令与补全来源

| 命令参数 | 补全来源 |
| --- | --- |
| `damage watch on/off` | literal；`targets` 使用原版实体选择器 |
| `heal targets` | 原版实体选择器 |
| `heal ... limb 部位` | 目标身体快照的 `BodyStructure.parts` |
| `heal ...` 模式 | literal：`all`、`health`、`limbs`、`stamina`、`zhenyuan` |

## 依赖

运行时硬依赖 Meridian Mod Framework、Human、Vanilla-Damage 和 Vanilla Humanoid Threatening；这些附属缺失时不提供降级路径。Java 17、Minecraft 1.20.1、Fabric Loader 0.16.10。

## 验证

```text
./gradlew test build :fabric:runGametest
```
