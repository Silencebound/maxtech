# CLAUDE.md — MaxTech Farms

Rules for the Farms addon (`maxtech_farms`), in development (milestone FM1, not released). The root `CLAUDE.md` holds the
rules for every mod; this file adds Farms'.

- **Lib api only.** Farms compiles against the `maxtech_lib-api` jar, like a third-party addon; Lib's internals are not
  on its compile classpath (`usesLibApiOnly` in build-logic). When Farms needs something internal, a Lib PR first
  promotes it into `lib.api` (Javadoc, a Lib minor bump, a changelog entry marked **Api**), then the Farms PR uses it.
- **Optional integrations:** Farms runs with Lib alone; Storage is an optional integration under
  `compat.maxtech_storage`.
