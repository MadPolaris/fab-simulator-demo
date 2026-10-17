# fab-simulator-demo

半导体 fab **设备仿真器**——用 Akka Typed actor 模拟 fab 设备（AMHS、stocker、
光刻机、CD-SEM、通用设备），做集成测试和演示时不用真实硬件。

[MadPolaris](https://github.com/MadPolaris) 引擎家族的一员。
从 [MadPolaris/playAkkaCQRS](https://github.com/MadPolaris/playAkkaCQRS) 抽离
（`fab-simulation/` 模块）。[English](README.md).

## 内容

| 包 | 内容 |
|----|------|
| `net.imadz.fab.simulation` | 设备仿真器：`AmhsSimulator`、`StockerSimulator`、`LithographySimulator`、`CdSemSimulator`、`GenericEquipmentSimulator`（+ `EquipmentSimulator` trait、`FabSimulationConfig`） |
| `net.imadz.fab.protocol` | `FabEquipmentProtocol` 消息词汇、`EquipmentAdapter` / `ActorEquipmentAdapter` |
| `net.imadz.fab.model` | `EquipmentArea`、`MeasurementResult` |

每个仿真器都是有状态的 `Behavior`——发协议消息给它，返回逼真的时序和响应。
设计上可接入更大的 fab 编排（比如 playAkkaCQRS 里的 M3.5 自愈演示），也可独立跑。

## 用法

```scala
import akka.actor.typed.scaladsl.Behaviors
import net.imadz.fab.simulation._

val litho = ctx.spawn(LithographySimulator.behavior(config), "litho-01")
litho ! FabEquipmentProtocol.Expose(wafers, replyTo)
```

## 构建

```bash
sbt compile
sbt test
```

## 说明

这是演示/支撑库，不是发布产物：永不发布到 Maven 中央仓（`publish / skip := true`）。

License: Apache-2.0.
