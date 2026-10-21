# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.5.1] - 2026-10-21

### Changed

- The status page shows the version at the right of its header with the date and time of the
  release, or of a local build, instead of how long ago it was built.

## [0.5.0] - 2026-10-21

### Added

- `run_sql_query` takes `rows` and `textValueCharacters`, which raise or lower the row limit and
  the length text values are shortened to, up to the environment's ceilings.
- `run_sql_query` with `format: toon` returns the answer in TOON, a more compact text format.
- The status page shows the running version, linked to its release on GitHub, and when it was
  built, and announces a newer release with a link to its notes. `update-check: false` turns the
  check off.

### Changed

- `max-rows` is now the ceiling a call may ask for, 5000 by default; the new `default-rows`, 500 by
  default, is what a query returns without asking. A default above the ceiling is lowered to it,
  so an existing `max-rows` keeps limiting every query as before.
- Text values are shortened to 200 characters by default instead of 4000 (`default-text-value-characters`),
  end with `…(+N)` giving the characters left out, and `notes` names the columns that were shortened.

## [0.4.1] - 2026-10-08

### Changed

- While the network does not reach a database, for example with the VPN disconnected, the
  connection pool no longer logs a warning with a stack trace at every retry. The environment going
  down is still logged once, with the reason.
- A tool call's arguments are logged up to 2000 characters each instead of 160, so most queries
  appear in the log whole.

## [0.4.0] - 2026-09-29

### Added

- Harbor registries as a fourth kind of environment, signed in with a robot account.
  `list_harbor_artifacts` lists the versions of an image with their tags, push and pull times, size
  and vulnerabilities by severity, and `harbor_api_request` reads any endpoint of the Harbor API.
- Once a database refuses the credentials of an environment signing in with a password, the server
  stops signing in to it, so that repeated attempts cannot lock a shared account. The status page
  shows a banner with a *Try once* button, and `list_environments` reports the refusal as
  `credentialRefusal`.

### Changed

- The server reads `secrets.env` itself at every start, so restarting the container is enough after
  changing a credential. `compose.yaml` no longer passes it with `env_file`, and `docker run` no
  longer needs `--env-file`. The file is read as a Java properties file: quotes around a value
  become part of it, and a backslash starts an escape sequence. On Linux, run the container as
  yourself to keep the file readable by you only.
- A PostgreSQL `boolean` comes back as `true` or `false` instead of `t` or `f`.
- A date-time at a full minute keeps its seconds, for example `2026-03-15T00:00:00` instead of
  `2026-03-15T00:00`.
- An Oracle `TIMESTAMP WITH TIME ZONE` comes back in ISO-8601 with its offset, a `TIMESTAMP WITH
  LOCAL TIME ZONE` in UTC, and an interval the way Oracle writes it, for example
  `+01 02:03:04.500000`.
- An environment waiting for its Entra sign-in is logged once at `INFO`, and the connection pool no
  longer repeats a stack trace while it waits.

### Fixed

- A PostgreSQL `numeric` holding `NaN` or `Infinity` failed the whole query.
- A PostgreSQL `infinity` date or timestamp came back as the largest date Java can hold; it now
  reads `infinity` or `-infinity`.
- An Oracle `BOOLEAN` that is null came back as `false`.
- An Oracle `XMLTYPE` value failed the query, as the driver lacked Oracle's XML libraries.

## [0.3.2] - 2026-09-25

### Added

- An environment that is up but lacks something the tools need carries a note, shown on the status
  page and reported by `list_environments`; a Grafana instance whose token sees no Loki datasource
  gets one.

### Fixed

- A Grafana instance whose token was deleted or had expired showed as up, because the probe asked
  the health endpoint, which needs no token. The probe now uses the token and reports such an
  instance as down.

## [0.3.1] - 2026-09-25

### Fixed

- Queries returning a PostgreSQL `timestamptz` column, such as `now()`, failed; the value now comes
  back with its offset, for example `2026-09-25T07:55:07Z`.

## [0.3.0] - 2026-09-24

### Added

- `pgpass-file` on an Entra account writes its current token into a PostgreSQL password file, so
  that psql, an IDE or another client reaches the same databases without a sign-in of its own.
- The status page shows for each Entra account where it writes its token, when it last did and why
  it could not.

### Fixed

- Building from a checkout on a machine without the git command failed; the version is then
  0.0.0-dev, as for a build without git history.

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

[Unreleased]: https://github.com/lubos-svoboda/mcp-hub/compare/v0.5.1...HEAD
[0.5.1]: https://github.com/lubos-svoboda/mcp-hub/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/lubos-svoboda/mcp-hub/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/lubos-svoboda/mcp-hub/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/lubos-svoboda/mcp-hub/compare/v0.3.2...v0.4.0
[0.3.2]: https://github.com/lubos-svoboda/mcp-hub/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/lubos-svoboda/mcp-hub/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/lubos-svoboda/mcp-hub/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/lubos-svoboda/mcp-hub/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/lubos-svoboda/mcp-hub/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/lubos-svoboda/mcp-hub/releases/tag/v0.1.0
