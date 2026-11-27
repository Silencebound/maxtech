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
- Sampler (`maxtech_farms:sampler`): hold use on a mob for 2 seconds to take a fresh specimen. Runs on MU
  (`sampler.muPerSample`, `sampler.capacityMu`), comes fully charged and takes FE from other mods' chargers.
