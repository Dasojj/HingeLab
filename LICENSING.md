# Licensing

Copyright (C) 2026 dasoj and Hinge Lab contributors.

Except for third-party material identified below or in individual files, Hinge Lab
is licensed under the GNU General Public License, version 3 only
(SPDX-License-Identifier: GPL-3.0-only).

Hinge Lab is free software: you can redistribute it and/or modify it under the
terms of the GNU General Public License as published by the Free Software
Foundation, version 3 of the License.

Hinge Lab is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
A PARTICULAR PURPOSE. See LICENSE for the full terms.

## Third-party material

Existing third-party copyright and license notices remain applicable. The GPL
for the combined application does not relicense third-party files exclusively
under GPL or remove their original permissions.

- ZFoldDuo, revision c8da65d5f8bba036ddeb17b491e7e8fa3d6ab0b1: MIT.
  Samsung angle-source approach, HingeProjection, HingeSceneView and
  spatial_projection were adapted and modified for Hinge Lab during September
  2026. Original notice: app/src/main/assets/ZFoldDuo-MIT.txt.
- SPAKE2-Java 1.1.1: GPL-3.0; used through Kadb for wireless ADB pairing.
- iPhone Duo Fold Preview (https://github.com/chuspeeism/iphone-duo),
  copyright 2026 jadon7, MIT: fixed-plane projection and source-coordinate
  blur/darkening adapted for the optional AGSL renderer. Notice:
  app/src/main/assets/licenses/iPhone-Duo-reference-MIT.txt. No Apple assets bundled.
- Other runtime dependencies retain Apache-2.0, MIT and their specified notices.
  See third_party/DEPENDENCIES.md and app/src/main/assets/licenses/.
- Third-party license texts and source excerpts in third_party/ remain
  under their original terms.

## Binary distribution

Distribute each APK together with access to its exact corresponding source and
build instructions, including required dependency sources and notices. The
release preparation bundle contains an application source archive, dependency
source files with a provenance manifest, and BUILDING.md. Do not substitute the
current default branch for the sources of an older APK.

Repository: https://github.com/Dasojj/HingeLab
Distribute the APK with access to its corresponding-source bundle.
