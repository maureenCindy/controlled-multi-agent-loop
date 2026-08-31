plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
}

group = "com.tenderpulse"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.13.13")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
        csv.required = false
    }
}

tasks.register("jacocoTestCoverageVerification") {
    dependsOn(tasks.jacocoTestReport)
    doLast {
        val jacocoSourceDir = "build/reports/jacoco/test/jacocoTestReport.xml"
        val sourceFile = file(jacocoSourceDir)
        if (!sourceFile.exists()) {
            throw RuntimeException("JaCoCo report not found at $jacocoSourceDir. Run 'gradle test' first.")
        }
        val xmlContent = sourceFile.readText()
        val instructionPattern = """<counter type="INSTRUCTION"[^>]*covered="(\d+)"[^>]*missed="(\d+)"""".toRegex()
        val match = instructionPattern.find(xmlContent)
        if (match != null) {
            val covered = match.groupValues[1].toLong()
            val missed = match.groupValues[2].toLong()
            val total = covered + missed
            val percentage = if (total > 0) (covered * 100 / total) else 0
            println("Code Coverage: $covered/$total ($percentage%)")
            if (percentage < 80) {
                throw RuntimeException("Code coverage is $percentage%, minimum required is 80%")
            }
        }
    }
}
