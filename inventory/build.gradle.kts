plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.bundles.service.base)
    implementation(libs.bundles.mysql)
    implementation(libs.bundles.kafka)
    implementation(libs.bundles.redis.cache)

    testImplementation(libs.bundles.service.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
