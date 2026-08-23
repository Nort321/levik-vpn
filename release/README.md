# Release inputs

This directory contains public, reviewable locks and schemas used to build
Android release evidence. It never contains APKs, AABs, private keys,
keystores, passwords, production configuration, or generated release output.

`native-sources.lock.json` binds the pinned native AAR to the exact source
revisions found in its Go build metadata and to the Go source version embedded
in the binary. The recorded archive sizes and SHA-256 digests are verification
gates; a changed GitHub-generated archive must be reviewed instead of silently
accepted.

The corresponding-source generator also captures the exact Android repository
revision, the complete Go module cache, module inventory, build scripts,
license files, and the official Go source archive. A bundle is release evidence,
not a claim that an independently rebuilt native binary is bit-for-bit
reproducible.
