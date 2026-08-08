plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "io.evotrace"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.5")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "io.evotrace.idea"
        name = "EvoTrace"
        version = project.version.toString()
        description = """
            EvoTrace — 系统演化追踪。在 IDEA 中右键文件查看演化历史与 AI 摘要，
            并打开项目面板查看统计与热点文件。
        """.trimIndent()
        ideaVersion {
            sinceBuild = "242"
            untilBuild = "252.*"
        }
        vendor {
            name = "EvoTrace"
            url = "https://github.com/evotrace"
        }
    }
}
