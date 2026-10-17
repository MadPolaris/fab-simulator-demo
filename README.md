# fab-simulator-demo

Semiconductor fab **equipment simulators** — Akka Typed actors that emulate fab
tools (AMHS, stocker, lithography scanner, CD-SEM, generic equipment) for
integration testing and demos without real hardware.

Part of the [MadPolaris](https://github.com/MadPolaris) engine family.
Extracted from [MadPolaris/playAkkaCQRS](https://github.com/MadPolaris/playAkkaCQRS)
(`fab-simulation/` module). [中文版](README-zh.md).

## What's inside

| Package | Contents |
|---------|----------|
| `net.imadz.fab.simulation` | Equipment simulators: `AmhsSimulator`, `StockerSimulator`, `LithographySimulator`, `CdSemSimulator`, `GenericEquipmentSimulator` (+ `EquipmentSimulator` trait, `FabSimulationConfig`) |
| `net.imadz.fab.protocol` | `FabEquipmentProtocol` message vocabulary, `EquipmentAdapter` / `ActorEquipmentAdapter` |
| `net.imadz.fab.model` | `EquipmentArea`, `MeasurementResult` |

Each simulator is a stateful `Behavior` — send it protocol messages, get realistic
timing and responses back. Designed to plug into a larger fab orchestration
(e.g. the M3.5 self-healing demo in playAkkaCQRS) or to run standalone.

## Use it

```scala
import akka.actor.typed.scaladsl.Behaviors
import net.imadz.fab.simulation._

val litho = ctx.spawn(LithographySimulator.behavior(config), "litho-01")
litho ! FabEquipmentProtocol.Expose(wafers, replyTo)
```

## Build

```bash
sbt compile
sbt test
```

## Note

This is a demo/support library, not a published artifact: it is never published
to Maven Central (`publish / skip := true`).

License: Apache-2.0.
