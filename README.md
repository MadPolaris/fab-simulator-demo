# fab-simulator-demo

Semiconductor fab **equipment simulators** + **demo web UI** — Akka Typed actors
that emulate fab tools (AMHS, stocker, lithography scanner, CD-SEM, generic
equipment), with a Play web frontend for the fab demo and M3.5 self-healing demo.

Part of the [MadPolaris](https://github.com/MadPolaris) engine family.
Extracted from [MadPolaris/playAkkaCQRS](https://github.com/MadPolaris/playAkkaCQRS)
(`fab-simulation/` module + fab web UI). [中文版](README-zh.md).

## Modules

| Module | Description |
|--------|-------------|
| `core/` | Equipment simulators: `AmhsSimulator`, `StockerSimulator`, `LithographySimulator`, `CdSemSimulator`, `GenericEquipmentSimulator` (+ protocol, model) |
| `web/` | Play 2.8 demo: fab simulation dashboard, M3.5 self-healing demo (3D view, fault injection, OCAP rules), WebSocket event streams |

The `web` module contains the full CQRS stack extracted from playAkkaCQRS
(`net.imadz.domain` / `net.imadz.application` / `net.imadz.infrastructure` —
133 files): aggregates, projections, sagas, routing, repositories.

## Run it

```bash
sbt "web / run"
```

- Fab demo: http://localhost:9000/fab-demo
- Fab designer: http://localhost:9000/fab-designer
- M3.5 self-healing: http://localhost:9000/fab-demo/m35
- M3.5 3D view: http://localhost:9000/fab-demo/m35-3d

Requires MongoDB at `127.0.0.1:27017` (journal + read models); see
`web/conf/application.conf`.

## Build

```bash
sbt compile
sbt "web / Test / compile"
```

## Note

This is a demo application, not a published artifact: it is never published
to Maven Central (`publish / skip := true`).

License: Apache-2.0.
