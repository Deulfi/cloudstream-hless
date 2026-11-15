# 2b. Bootstrap repositories and extensions

Read [README.md](README.md) first. Depends on: 2a.

## Goal

At start, make the engine's installed extensions match the environment
variables, so the container needs no manual API calls.

## File to create

`engine/src/main/kotlin/com/lagradost/hless/Bootstrap.kt` with
`suspend fun bootstrap(client: EngineClient)`.

## Behaviour

For each URL in `Config.repos`, in order:

1. `client.syncRepo(url, installAll = false)`.
2. If that throws `EngineException` with code `consent_required`: call
   `client.consent(url)`, then `syncRepo` again once.
3. `client.repoPlugins(url)` gives the catalog. Choose entries:
   - `Config.plugins == ["*"]`: all of them.
   - otherwise: those whose `internalName` equals a listed name, ignoring case.
4. For each chosen entry that is not installed yet (`installationId` is null),
   call `client.install(url, internalName)`.
5. Log one line per extension: installed, already installed, or failed with
   the engine's `code` and message.

Rules:

- One repository failing must not stop the others. Catch per repository and
  per extension, log, continue.
- `/repos/sync` allows about one call every 2 seconds (burst 4). Wait 2
  seconds between repositories.
- Never uninstall anything here. Removing an extension is a manual action.
- An empty `HLESS_REPOS` is fine: log "no repositories configured" and return.
- After the loop, log `client.plugins()` as a table: name, state, provider
  count, `lastError`.

Updates need no code: the engine re-syncs repositories every 6 hours by
itself.

## Tests

Extract the choice in step 3 into a pure function
`choosePlugins(catalog: List<RepoPluginDto>, wanted: List<String>): List<RepoPluginDto>`
and test it in `engine/src/test/kotlin/com/lagradost/hless/BootstrapTest.kt`:
`*` selects all, names match case-insensitively, unknown names select nothing.

## Done when

On the test container, with `HLESS_REPOS` set to one real repository URL (ask
the maintainer for one; do not commit repository URLs to this repo), a restart
ends with at least one extension in state `running` in the log table, and a
second restart reports them as already installed.
