# Credits

All textures in MaxTech Storage and MaxTech Lib are original.

MaxTech Lib bundles GuideME (https://github.com/AppliedEnergistics/GuideME) unchanged inside its jar (jar-in-jar,
`META-INF/jarjar/guideme-<version>.jar`); it draws the guides of every MaxTech mod. GuideME is licensed under LGPLv3,
MIT and Apache 2.0 (its POM). The nested jar carries its notices unchanged: `NOTICE` (the MIT notices of the Markdown
libraries it ports), `META-INF/LICENSE.txt` and `META-INF/NOTICE.txt` (Apache 2.0, the Lucene it shades). The LGPLv3
text travels in Lib's jar as `META-INF/COPYING.LESSER` and `META-INF/COPYING`, as for Lib itself (LGPL-3.0-only).
MaxTech Storage no longer bundles its own copy; it uses Lib's.

The guide website's page template (`build-logic/guide-web/templates/layout.html`, build tooling only, not in any jar)
is a modified copy of GuideME's `layout.html` (LGPLv3).
