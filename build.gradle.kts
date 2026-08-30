plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
    mavenLocal()
}

group = "cloud.aster-lang"

// Maven 制品版本 = 共享版本目录的 asterLang（JVM 生态单一版本源，ADR 0012/0023 §9）。
// 不硬编码字面量——字面量是版本漂移的来源。从 catalog 派生让版本永远跟随 ecosystemVersion。
// 与 core/truffle/runtime/locales/hi 同构（settings 已声明 asterLibs catalog）。
version = extensions.getByType<VersionCatalogsExtension>()
    .named("asterLibs").findVersion("asterLang").get().requiredVersion

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "aster-lang-validation"
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/aster-cloud/${rootProject.name}")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    // 日志
    implementation("org.slf4j:slf4j-api:2.0.9")

    // 测试 — 版本走 asterLibs catalog（platform#77）。
    // ★catalog 自称第三方版本的 single source of truth，本仓此前却硬编码
    //   assertj 3.27.6 / junit 6.0.0 字面量——于是「改 catalog」并不会改到这里，
    //   single source 的承诺落空。改用别名后版本只在 platform 的 catalog 里定义。
    //   注：slf4j / mockito / logback 目前**不在** catalog 治理内（catalog 里没有
    //   对应 version 与 library），故仍是字面量；收敛它们需先在 platform 侧登记。
    testImplementation(asterLibs.junit.jupiter)
    testImplementation(asterLibs.assertj.core)
    testImplementation("org.mockito:mockito-core:5.5.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.19")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf(
        "-parameters",  // 保留参数名
        "-Xlint:deprecation",
        "-Xlint:unchecked"
    ))
}
