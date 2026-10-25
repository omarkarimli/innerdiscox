# Contributing

Contributions are welcome through the `omarkarimli/innerdiscox` GitHub
repository.

## Development Setup

Requirements:

- JDK 21
- Android SDK with API 37

Enable the version-controlled pre-commit hook once after cloning:

```bash
git config core.hooksPath .githooks
```

The hook runs `ktlintCheck` and `apiCheck`. It does not modify or stage files;
run `ktlintFormat` or `desktopApiDump` explicitly when needed and review the
resulting changes.

Run the complete local gate before opening a pull request:

```bash
./gradlew ktlintFormat
./gradlew allTests ktlintCheck apiCheck assemble publishToMavenLocal
```

`ktlintFormat` changes source files. Review those changes before committing.

## Changes

- Keep the library independent of Disco application settings, models,
  databases, UI, and playback frameworks.
- Add regression tests for protocol parsing, session handling, cipher changes,
  and SABR behavior.
- Never add cookies, signed media URLs, PO tokens, captured responses, account
  identifiers, or other credentials to tests, fixtures, logs, or issues.
- Derive locale from caller settings or the system locale. Do not add hardcoded
  language or country fallbacks.
- Preserve cancellation and close response bodies on unsuccessful HTTP paths.
- Keep public API minimal. For an intentional public API change, run
  `./gradlew desktopApiDump`, review `api/desktop/innerdiscox.api`, and commit
  the generated baseline with the source change. Do not edit it manually.
- Run `./gradlew apiCheck` after regenerating the baseline. If no public API
  change is intended, investigate any API diff instead of accepting it.

## Commit and Pull Request Scope

Prefer focused changes with a clear reason. A pull request should describe the
behavioral change, tests run, and any known interoperability risk with Disco
clients or playback transports.

By participating, you agree to follow [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
