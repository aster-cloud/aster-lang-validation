rootProject.name = "aster-lang-validation"

// NOTE (issue #6, task 4 — deferred): adoption of the shared `aster-lang-platform`
// version catalog is intentionally NOT done here. The CI workflows
// (.github/workflows/ci.yml, release.yml) only configure mavenCentral()/mavenLocal()
// and run `./gradlew build` directly — they do not make the platform catalog
// available (no extra checkout / mavenLocal priming / catalog dependency). Wiring a
// `versionCatalogs { ... from(...) }` block now would break CI resolution. Revisit
// once CI publishes/exposes the catalog; at that point also reconsider whether
// slf4j-api should move from `implementation` to `compileOnly`/`api` for a library.
