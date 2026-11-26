# Contributing

Contributions are accepted under the GNU LGPL v3.0 only (see [LICENSE](LICENSE)). By submitting a contribution you
agree that the maintainer (Silencebound) may relicense it under any other OSI-approved license in future versions of
the mods, so that relicensing stays possible.

- **Bugs and ideas:** open an issue with the bug report or feature request form. Security problems go through a private
  report instead ([SECURITY.md](SECURITY.md)).
- **Pull requests:** open an issue first for anything larger than a small fix, so the change fits the plans in
  [PRD.md](PRD.md) and [storage/PRD.md](storage/PRD.md). Run `./gradlew spotlessApply` and `./gradlew build` before
  pushing; a bug fix comes with a test that fails without it (JUnit for plain logic, a GameTest for behaviour in a
  level).
- **Commits:** Conventional Commits (`fix(storage): ...`, `feat(lib): ...`), imperative, a short first line (at most 72
  characters).
