// SPDX-License-Identifier: Apache-2.0
// SV-P1 S4 T6 — standalone (NOT part of the tatrman-server build). Deliberately
// has its own settings so it resolves ONLY from Maven Central, with no access to
// the repo's GitHub Packages credentials or the multi-module build.
rootProject.name = "verify-public-resolution"
