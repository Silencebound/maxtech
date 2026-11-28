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
  at its burn time (`hub.fuelMuPerTick`), and a sampler charging slot (`hub.samplerChargeMuPerTick`; use a sampler on
  the hub to put it in, sneak-use with an empty hand to take it out). A burnt fuel's remainder (the
  empty bucket of lava) goes into the output slots. It drops with everything it holds, with any tool and in
  explosions. Its tank and slots are Lib's inventories. Only an empty hand opens its screen; any other held item
  (lab tubes, a fluid port, any block) is placed or used as usual. The screen: tank (bucket clicks fill and drain
  it), MU bar, fuel slot and flame, sampler slot and its charge (shown while a sampler is in), the output and a
  status line.
- Lab tube (`maxtech_farms:lab_tube`): a Lib conduit (dyes and facades work) that joins one lab hub, its fluid
  ports and later the specimen blocks into a lab network carrying nutrient, MU and output. A lab takes one hub: a
  hub placed on tubes that already have a running hub stays idle and says so (it still burns fuel into its own store).
  The lowest hub (y, then x, then z) runs the lab only when no hub runs it yet, as after a restart, or when two labs
  with a running hub each are joined. A hub beside two separate lab networks runs only the first that took it; the
  other has no hub (a misconfiguration), and when the first lets it go it no longer counts as running there. The hub's
  MU store is the lab's energy buffer. Tubes are cyan glass with gunmetal borders and show the hub tank's fluid and
  level (in quarters) flowing from the hub along the tubes.
  Bends and branches have a gunmetal frame around the core's outer faces, so the borders meet it there.
- Lab fluid port (`maxtech_farms:fluid_port`): pipes and pumps push fluid into the lab hub's tank through it; it never
  pulls or gives fluid back.
- Creative tab "MaxTech Farms" with the lab hub, fluid port, lab tube and sampler; they are no longer listed in the
  vanilla tabs.
