# Contributing to the Jikū backend

This guide is the workflow every change follows, from picking a story to merging
it. The engineering rules themselves (module boundaries, configuration, tests,
commits) live in `AGENTS.md`; read it before your first change.

## 1. One story, one branch, merged before the next

1. Pick the next story in the production plan (`docs/jiku-plan-production.md`)
   and give it its `JIKU-<n>` number.
2. Branch from an up-to-date `develop`:

   ```bash
   git fetch origin develop
   git checkout -b jiku-<n>-<slug> origin/develop
   ```

3. Build the story, its tests and its documentation on that branch.
4. Open a pull request against `develop`, as a draft while work is in progress.
5. **Merge the story into `develop` before starting the next one.** A story is
   finished only when its pull request is squash-merged; the next branch starts
   from the `develop` that contains it.

Never stack a story on another unmerged story. Stacked branches drift from each
other, every squash-merge below them turns into a conflict above them, and a
reviewer can no longer read one story in isolation. If a story depends on
unmerged work, finish and merge that work first.

A story that touches both services (for example a backend endpoint and the screen
that uses it) has one branch per repository, both named `jiku-<n>-<slug>`. The
backend is merged first; the frontend follows once the contract it consumes is on
`develop`.

## 2. Before opening the pull request

Every check below must pass locally. CI runs the same ones and blocks the merge
otherwise.

```bash
./gradlew ktlintFormat                                  # then commit the result
./gradlew ktlintCheck
./gradlew test --tests "com.jiku.ModularityTests"       # module boundaries
./gradlew regenerateOpenApi                             # when an endpoint changed
./gradlew build koverVerify                             # full suite + 70 % coverage
```

- Tests cover the story's acceptance criteria, including cross-tenant isolation
  and the concurrency cases the story names.
- Every new environment variable is documented in `.env.example`.
- A new migration is additive and never edits one already merged.
- Dead code left behind by the change is removed in the same change.

## 3. Commits and pull requests

- Conventional Commits: `<type>(<module>): <description>` with a
  `Refs: JIKU-<n>` trailer. Types: `feat`, `fix`, `refactor`, `test`, `docs`,
  `chore`, `perf`, `build`.
- The pull request title is the squash commit title; the description says what
  changed and why, which acceptance criteria the tests cover, and any behaviour a
  client of the API will notice (use `.github/pull_request_template.md`).
- No AI authorship trace anywhere: not in commits, pull requests, comments or file
  headers.

## 4. Merging

1. CI is green on the latest commit and every review thread is answered.
2. Mark the pull request ready and **squash-merge** it into `develop`.
3. Delete the branch.
4. Only then start the next story from the updated `develop`.

`main` receives `develop` for a release; nothing is committed to `main` or
`develop` directly.
