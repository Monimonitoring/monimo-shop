import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
}

val javaVersion = libs.versions.java.get().toInt()

// 쇼핑몰은 감시 대상이라 제품 기능이 없다. 서비스끼리는 HTTP 로만 부르고 코드로는 서로 의존하지 않는다.
// 모듈 간 project 의존을 걸면 빌드가 바로 실패한다 (Service map 에 HTTP 홉이 그려져야 하므로).
subprojects {
    group = "com.monimo.shop"
    version = "0.0.1-SNAPSHOT"

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(javaVersion)
            compilerOptions {
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }
    }

    plugins.withId("org.springframework.boot") {
        // Docker 이미지에는 실행용 jar(bootJar) 하나만 필요하므로 일반 jar 는 만들지 않는다.
        tasks.withType<Jar>().matching { it.name == "jar" }.configureEach { enabled = false }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    val modulePath = path
    configurations.configureEach {
        dependencies.withType<ProjectDependency>().configureEach {
            error("모듈 경계 위반: $modulePath 가 $path 에 의존합니다. 쇼핑몰 서비스끼리는 HTTP 로만 부르고 코드로는 의존하지 않습니다.")
        }
    }
}
