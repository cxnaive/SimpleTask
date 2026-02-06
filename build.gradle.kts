plugins {
    java
    `java-library`
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.user"
version = "1.0.0"

dependencies {
    // Folia API
    compileOnly("dev.folia:folia-api:1.21.11-R0.1-SNAPSHOT")

    // XConomy API (软依赖)
    compileOnly("com.github.YiC200333:XConomyAPI:2.25.1")

    // CraftEngine (软依赖)
    compileOnly("net.momirealms:craft-engine-bukkit:0.0.67")
    compileOnly("net.momirealms:craft-engine-core:0.0.67")

    // Database (打包进JAR)
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("com.h2database:h2:2.3.232")
    implementation("com.mysql:mysql-connector-j:9.2.0")

    // JSON
    implementation("com.google.code.gson:gson:2.12.1")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://repo.momirealms.net/releases/")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks {
    shadowJar {
        archiveClassifier.set("")

        // 重定位数据库驱动
        relocate("org.h2", "dev.user.simpletask.libs.org.h2")
        relocate("com.zaxxer", "dev.user.simpletask.libs.com.zaxxer")
        relocate("com.mysql", "dev.user.simpletask.libs.com.mysql")
        relocate("com.google.gson", "dev.user.simpletask.libs.com.google.gson")

        // 合并服务文件 (H2驱动注册)
        mergeServiceFiles {
            include("META-INF/services/java.sql.Driver")
        }
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
