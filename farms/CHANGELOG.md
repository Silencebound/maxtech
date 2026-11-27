# MaxTech Farms changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow semver (PRD §5.8).
Api changes are marked **Api**.

## [Unreleased]

### Added
- The mod itself: an empty `maxtech_farms` on Lib's api.
- Server config `maxtech_farms-server.toml` with presets (`standalone`, `pack`); values arrive with the features that
  read them. The `presets_complete` GameTest checks that the presets and the values match.
- Specimen item (`maxtech_farms:specimen`): what was sampled, generation, Growth, Gain, Strength and up to two traits;
  identical specimens stack to 16.
- Sampler (`maxtech_farms:sampler`): hold use on a mob for 2 seconds (a bar under the crosshair fills) to take a
  fresh specimen; a refused sample lets the mob's own interaction happen. Runs on MU
  (`sampler.muPerSample`, `sampler.capacityMu`), comes fully charged and takes FE from other mods' chargers.
- Lab hub (`maxtech_farms:lab_hub`): a nutrient tank (`hub.tankCapacity`, 16 buckets), 27 output slots other mods
  empty, an MU store (`hub.energyCapacity`) charged by FE at Lib's rate and by a fuel slot that burns any furnace fuel
  at its burn time (`hub.fuelMuPerTick`); no screen yet. It drops with everything it holds.
- Lab tube (`maxtech_farms:lab_tube`): a Lib conduit (dyes and facades work) that joins one lab hub, its fluid
  ports and later the specimen blocks into a lab network carrying nutrient, MU and output. A lab takes one hub; a
  second hub on the same tubes stays idle and says so. The hub's MU store is the lab's energy buffer.
- Lab fluid port (`maxtech_farms:fluid_port`): pipes and pumps push fluid into the lab hub's tank through it; it never
  pulls or gives fluid back.
