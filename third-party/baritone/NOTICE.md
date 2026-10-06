Lodekeeper bundles the unmodified Baritone Fabric API jar as a nested Fabric mod. `dependencies.json` maps each exact Minecraft profile to the Baritone release, source commit, and SHA-256 checked by the fetch script. The matching source is available at `https://github.com/cabaletta/baritone/tree/<source_commit>`.

Baritone is licensed under the GNU Lesser General Public License, version 3 or later. The complete upstream license is in `licenses/COPYING.LESSER`. Its incorporated GNU GPL terms are in `licenses/COPYING`.

To build Lodekeeper with a modified or replacement Baritone jar, build a Fabric jar from the matching Baritone source commit and pass `-Pbaritone_jar=/absolute/path/to/baritone-api-fabric.jar` to the same Gradle build command for that Minecraft profile. The build checks the replacement's Fabric mod id, Minecraft dependency, and Java class version before packaging it as a nested Fabric mod. The pinned release SHA check applies to downloaded upstream jars; a local replacement uses its own SHA and still passes the manifest and class checks.

Lodekeeper's own code keeps its separate MIT license in the repository's root `LICENSE` file. Baritone's LGPL terms apply to Baritone.

Baritone releases also contain a nested `nether-pathfinder` jar. The embedded `1.4.1` and `1.6` jars inspected for this map contain no license file. The `nether-pathfinder` v1.6 source tree at commit `1f3daf8` has no license file, and its published POM has no license declaration. Its license is not assigned here. The inspected source and POM are linked in `dependencies.json`.

Release source bundles are produced by `scripts/package-baritone-sources.py`. They include every pinned Baritone source archive, both nested native-library sources, the exact Abseil and zlib-ng submodule archives, a SHA-256 manifest, and replacement-build instructions. The source bundle accompanies releases that contain this dependency.
