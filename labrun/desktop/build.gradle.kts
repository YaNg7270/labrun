import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("labrun.examples", rootProject.projectDir.parentFile.resolve("examples").absolutePath)
}

compose.desktop {
    application {
        mainClass = "labrun.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "LabRun"
            packageVersion = "1.1.0"
            description = "LabRun desktop (experiment records)"
            vendor = "LabRun"
            windows {
                menu = true
                menuGroup = "LabRun"
                shortcut = true
                upgradeUuid = "6b1f8f53-7c2a-4d55-9a3e-2f6d8e9b1c41"
            }
        }
    }
}
