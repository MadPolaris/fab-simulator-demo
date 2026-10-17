# fab-simulator-demo

半导体 fab **设备仿真器** + **演示网页**——用 Akka Typed actor 模拟 fab 设备
（AMHS、stocker、光刻机、CD-SEM、通用设备），配 Play 网页做 fab 演示和
M3.5 自愈演示。

[MadPolaris](https://github.com/MadPolaris) 引擎家族的一员。
从 [MadPolaris/playAkkaCQRS](https://github.com/MadPolaris/playAkkaCQRS) 抽离
（`fab-simulation/` 模块 + fab 网页 UI）。[English](README.md).

## 模块

| 模块 | 说明 |
|------|------|
| `core/` | 设备仿真器：`AmhsSimulator`、`StockerSimulator`、`LithographySimulator`、`CdSemSimulator`、`GenericEquipmentSimulator`（+ protocol、model） |
| `web/` | Play 2.8 演示：fab 仿真看板、M3.5 自愈演示（3D 视图、故障注入、OCAP 规则）、WebSocket 事件流 |

`web` 模块包含从 playAkkaCQRS 抽出的完整 CQRS 栈
（`net.imadz.domain` / `net.imadz.application` / `net.imadz.infrastructure`，
133 个文件）：聚合、投影、saga、路由、仓储。

## 运行

```bash
sbt "web / run"
```

- Fab 演示：http://localhost:9000/fab-demo
- Fab 设计器：http://localhost:9000/fab-designer
- M3.5 自愈：http://localhost:9000/fab-demo/m35
- M3.5 3D 视图：http://localhost:9000/fab-demo/m35-3d

需要 MongoDB（`127.0.0.1:27017`，journal + 读模型），见 `web/conf/application.conf`。

## 构建

```bash
sbt compile
sbt "web / Test / compile"
```

## 说明

这是演示应用，不是发布产物：永不发布到 Maven 中央仓（`publish / skip := true`）。

License: Apache-2.0.
