# Changelog

All notable repository-level changes are documented here. Android component
release notes may add detail but must not contradict this file.

The format follows Keep a Changelog principles. Version tags and release dates
will be introduced only after source control and the release process are
established.

## Unreleased

### Added

- Root repository safety, governance, contribution, security, licensing, and
  third-party notice files.
- Android architecture, release, security, and signing documentation.
- Repository checks for forbidden tracked files and mutable GitHub Action
  references.
- A fail-closed downloader that obtains the pinned official libXray archive and
  verifies both archive and extracted AAR SHA-256 values.
- GitHub contribution templates and Gradle/GitHub Actions dependency updates.

### Changed

- Declared the repository Android-only and private-first; this does not assert
  that GitHub visibility or any later public-source publication is complete.
- Excluded `levik_vpn_landing/` from Git and made tracked website/server paths a
  repository-policy failure.
- Limited Dependabot configuration to Gradle and GitHub Actions dependencies.

### Removed

- Server deployment, rollback, and backup runbooks that did not describe an
  included repository component.

### Known limitations

- Hosted CI, protected release environments, production Android signing, and
  artifact publication are not yet established by these repository files.
- Android binary distribution remains blocked until the libXray/Xray-core/sing
  notice and corresponding-source obligations are assembled and verified for
  the exact shipped artifact.
- Website, backend, mobile BFF, bot, bridge, and production infrastructure are
  outside this repository and receive no open-source or release claim here.
