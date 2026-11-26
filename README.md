# MaxTech Lib and Storage

NeoForge mods for Minecraft 26.1.2 (Java 25).

- **MaxTech Storage** (`maxtech_storage`) is a digital storage network with autocrafting: channel-limited cables and hubs, drives and cells, terminals, ports, a controller cluster, research and optional AE2 and Refined Storage bridges. It runs on FE and needs only MaxTech Lib.
- **MaxTech Lib** (`maxtech_lib`) is the code library Storage builds on: the conduit and network frameworks, FE helpers, GUI components, config and presets, content and datagen helpers. It publishes an `-api` jar for addons.

Each mod is split into `common` (game logic against Minecraft and the `Platform` interface) and `neoforge` (the loader glue). NeoForge is the only supported loader.

## Building

Requires JDK 25.

```
./gradlew build                                 # build and test everything
./gradlew :storage:neoforge:runClient           # dev client with Lib and Storage
./gradlew :storage:neoforge:runGameTestServer   # GameTests
./gradlew :storage:neoforge:runData             # datagen
./gradlew spotlessApply                         # format
```

## Documentation

- [PRD.md](PRD.md) holds the shared requirements and [storage/PRD.md](storage/PRD.md) Storage's.
- Architecture decisions are in [docs/adr](docs/adr).
- Contributing and security reports: [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md).
- Credits for third-party assets: [CREDITS.md](CREDITS.md).

## License

MaxTech Lib and MaxTech Storage are licensed under the GNU LGPL v3.0 only (`LGPL-3.0-only`; see [LICENSE](LICENSE),
`COPYING.LESSER` and `COPYING`). In plain words: you may use them in modpacks and build addons against them, but changes
to the mods' own code must stay open under the same license.

## Modpacks

Free to use in any modpack, public or private.
