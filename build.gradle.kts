plugins {
    java
    `java-library`
    id("com.gradleup.shadow") version "9.3.1"
}

group = "dev.user"
version = "2.0.2"

dependencies {
    // Folia API
    compileOnly("dev.folia:folia-api:26.2.build.7-beta")

    // XConomy API (软依赖)
    compileOnly("com.github.YiC200333:XConomyAPI:2.25.1")

    // CraftEngine (软依赖)
    compileOnly("net.momirealms:craft-engine-core:26.7")
    compileOnly("net.momirealms:craft-engine-bukkit:26.7")

    // NBT API
    compileOnly("de.tr7zw:item-nbt-api-plugin:2.16.1")

    // PlaceholderAPI (软依赖)
    compileOnly("me.clip:placeholderapi:2.12.3")

    // Database & JSON (由服务器通过 plugin.yml libraries 加载)
    compileOnly("com.zaxxer:HikariCP:6.2.1")
    compileOnly("com.h2database:h2:2.3.232")
    compileOnly("com.mysql:mysql-connector-j:9.2.0")
    compileOnly("com.google.code.gson:gson:2.12.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
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
        languageVersion = JavaLanguageVersion.of(25)
    }
}

sourceSets.test {
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().compileClasspath
}

val validationProbe = sourceSets.create("validationProbe") {
    java.srcDir("validation/folia/probe")
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}

tasks.register<Jar>("validationProbeJar") {
    dependsOn(tasks.named(validationProbe.classesTaskName))
    archiveFileName.set("SimpleTaskValidation.jar")
    from(validationProbe.output)
    from("validation/folia/probe-plugin.yml") { rename { "plugin.yml" } }
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    shadowJar {
        archiveClassifier.set("")
    }

    processResources {
        inputs.property("version", project.version)
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
