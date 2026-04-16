plugins {
    java
    `java-library`
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.user"
version = "1.1.0"

dependencies {
    // Folia API
    compileOnly("dev.folia:folia-api:1.21.11-R0.1-SNAPSHOT")

    // XConomy API (软依赖)
    compileOnly("com.github.YiC200333:XConomyAPI:2.25.1")

    // CraftEngine (软依赖)
    compileOnly("net.momirealms:craft-engine-bukkit:0.0.67")
    compileOnly("net.momirealms:craft-engine-core:0.0.67")

    // NBT API
    compileOnly("de.tr7zw:item-nbt-api-plugin:2.15.5")

    // PlaceholderAPI (软依赖)
    compileOnly("me.clip:placeholderapi:2.11.6")

    // Database & JSON (由服务器通过 plugin.yml libraries 加载)
    compileOnly("com.zaxxer:HikariCP:6.2.1")
    compileOnly("com.h2database:h2:2.3.232")
    compileOnly("com.mysql:mysql-connector-j:9.2.0")
    compileOnly("com.google.code.gson:gson:2.12.1")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://repo.momirealms.net/releases/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks {
    shadowJar {
        archiveClassifier.set("")
    }

    processResources {
        filesMatching("plugin.yml") {
            expand("version" to version)
        }
    }

    build {
        dependsOn(shadowJar)
    }

    compileJava {
        options.encoding = "UTF-8"
    }
}
