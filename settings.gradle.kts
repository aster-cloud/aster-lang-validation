rootProject.name = "aster-lang-validation"

dependencyResolutionManagement {
    // 共享版本目录（aster-lang-platform，ADR 0012/0023 §9）：本仓 Maven 制品版本从
    // catalog 的 asterLang 派生（消除字面量漂移）。此前 issue #6 deferred 的理由是 CI 不
    // prime catalog → 加 from(...) 会破配置期解析；本次同 PR 同步给 ci.yml/release.yml
    // 加 checkout platform + publishToMavenLocal bootstrap，deferred 的反对理由已消解。
    // 本仓零 aster 依赖，catalog 仅为版本派生而引入——消漂移的必要代价。
    @Suppress("UnstableApiUsage")
    repositories {
        mavenLocal()
        mavenCentral()
    }
    versionCatalogs {
        create("asterLibs") {
            from("cloud.aster-lang:aster-lang-platform:1.0.18")
        }
    }
}
