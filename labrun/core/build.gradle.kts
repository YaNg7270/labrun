plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // 固定案例文件位于仓库根的 examples/
    systemProperty("labrun.examples", rootProject.projectDir.parentFile.resolve("examples").absolutePath)
}
