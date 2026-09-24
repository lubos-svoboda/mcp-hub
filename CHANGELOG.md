# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `pgpass-file` on an Entra account writes its current token into a PostgreSQL password file, so
  that psql, an IDE or another client reaches the same databases without a sign-in of its own.

## [0.2.0] - 2026-09-24

### Added

- Microsoft Entra ID sign-in for Azure Database for PostgreSQL: `authentication: ENTRA`,
  `mcp-hub.entra-accounts`, sign-in on the status page, automatic token renewal, connections tagged
  with the signed-in user, `expected-user`.
- Status page: Entra accounts with sign-in and sign-out, a banner for accounts waiting for their
  sign-in, *Check now* and *Check* per environment, a spinner while checking, relative local times.
- `list_environments` reports the sign-in state of Entra environments; `mcp-hub.status-page-url`.
- Requests addressed to a foreign host name or sent from a foreign web page are refused;
  `mcp-hub.additional-allowed-hosts`.
- A GitHub Release for every version tag.

### Fixed

- Queries returning a PostgreSQL `date` column failed.

## [0.1.1] - 2026-09-24

### Changed

- The image listens on every interface and reads `/config/application.yaml` on its own, so the
  quick start is a single `docker run` without a clone or a Compose file.

## [0.1.0] - 2026-09-24

### Added

- First release: one MCP server for Oracle, PostgreSQL and Grafana environments, with read-only
  queries, schema tools including views, execution plans, Grafana and Loki tools, a status page
  and parallel connection probes.
- Versions come from git tags; images for `linux/amd64` and `linux/arm64` are published to
  `ghcr.io/lubos-svoboda/mcp-hub`.

[Unreleased]: https://github.com/lubos-svoboda/mcp-hub/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/lubos-svoboda/mcp-hub/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/lubos-svoboda/mcp-hub/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/lubos-svoboda/mcp-hub/releases/tag/v0.1.0
