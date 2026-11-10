plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.shadow.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)
    implementation(libs.vanniktech.maven.publish.gradle.plugin)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
