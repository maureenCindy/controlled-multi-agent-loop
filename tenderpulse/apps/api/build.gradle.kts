plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    kotlin("plugin.jpa") version "2.0.21"
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
    implementation("org.jsoup:jsoup:1.18.1")

    // TP-048: the running app connects to real PostgreSQL only (see application.yml). H2 is no
    // longer on the app's runtime classpath — it now backs the test suite only (see
    // src/test/resources/application.yml and apps/api/README.md "Tests vs. the real datasource").
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.13.13")
    testRuntimeOnly("com.h2database:h2")
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

    // Report the real, report-level coverage total in the build log. The counters are read via
    // an XML parser rather than a regex: the report contains one <counter> per method, class and
    // package as well as the totals, and a regex over the raw text matches whichever comes first
    // in document order (a single method) instead of the report-level total.
    val reportXml = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")
    doLast {
        val total = readReportInstructionCounter(reportXml.get().asFile)
        if (total == null) {
            logger.warn("Could not read report-level INSTRUCTION counter from ${reportXml.get().asFile}")
        } else {
            val (covered, missed) = total
            val instructions = covered + missed
            val percentage = if (instructions > 0) covered * 100 / instructions else 0
            logger.lifecycle("Code Coverage (INSTRUCTION, whole report): $covered/$instructions ($percentage%)")
        }
    }
}

/**
 * Reads the report-level INSTRUCTION counter (covered, missed) from a JaCoCo XML report.
 *
 * Only counters that are direct children of the root <report> element are totals; counters
 * nested inside <package>, <class> and <method> are per-element and must not be read as the
 * overall figure. External DTD loading is disabled so the parser does not reach out to the
 * JaCoCo DTD over the network.
 */
fun readReportInstructionCounter(reportFile: File): Pair<Long, Long>? {
    if (!reportFile.exists()) {
        throw GradleException("JaCoCo report not found at $reportFile. Run 'gradle test' first.")
    }
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isValidating = false
    }
    val root = factory.newDocumentBuilder().parse(reportFile).documentElement
    val children = root.childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeName != "counter") continue
        val attrs = node.attributes ?: continue
        if (attrs.getNamedItem("type")?.nodeValue != "INSTRUCTION") continue
        val covered = attrs.getNamedItem("covered")?.nodeValue?.toLongOrNull() ?: continue
        val missed = attrs.getNamedItem("missed")?.nodeValue?.toLongOrNull() ?: continue
        return covered to missed
    }
    return null
}

// Minimum instruction coverage the build enforces. Override on the command line or in CI with
// -PminCoverage=0.80.
//
// This is a ratchet set just under the current measured figure (57%), not the project target.
// The target remains 80%; the notification and api packages are currently at 0% because the
// features they hold are still unbuilt (TP-010, TP-012). Raise this number as those land — it
// exists to stop coverage sliding backwards, not to certify that 80% has been reached.
val minCoverage: String by extra((findProperty("minCoverage") as String?) ?: "0.55")

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = minCoverage.toBigDecimal()
            }
        }
    }
}
