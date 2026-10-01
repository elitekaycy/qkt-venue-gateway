## What and why

## Validation

- [ ] `./gradlew build` and `scripts/check-rules.sh` pass locally
- [ ] Tests added for every behaviour changed; venue facts from recorded fixtures
- [ ] Wire-spec, design or README updated where behaviour changed
- [ ] Adapter branch: `scripts/check-adapter.sh <venue>` passes, contract suite run on the test
      environment, end-to-end run through qkt noted below
- [ ] No secrets, `.env` or runtime state in the diff
