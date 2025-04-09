import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group = "com.github.BonnierNews"
version = "1.0"

android {
    namespace = "se.bonniernews.bnappauth_android"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_21.toString()
    }

    tasks.withType<Test>().configureEach {
        jvmArgs("-XX:+EnableDynamicAgentLoading")
        testLogging {
            events = setOf(
                TestLogEvent.PASSED,
                TestLogEvent.FAILED,
                TestLogEvent.SKIPPED,
                TestLogEvent.STANDARD_OUT,
                TestLogEvent.STANDARD_ERROR
            )

            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
        addTestListener(object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {}
            override fun beforeTest(testDescriptor: TestDescriptor) {}
            override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}

            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                if (suite.parent == null) { // root suite
                    logger.lifecycle("----")
                    logger.lifecycle("Test result: ${result.resultType}")
                    logger.lifecycle(
                        "Test summary: ${result.testCount} tests, " +
                                "${result.successfulTestCount} succeeded, " +
                                "${result.failedTestCount} failed, " +
                                "${result.skippedTestCount} skipped"
                    )
                }
            }
        })
        ignoreFailures = false
    }
}

dependencies {
    implementation("net.openid:appauth:0.11.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
    testImplementation("org.robolectric:robolectric:4.11.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.BonnierNews"
            artifactId = "bn-app-auth-sdk-aar"
            version = System.getenv("RELEASE_VERSION") ?: "1.0.0"
            artifact("$buildDir/outputs/aar/BNAppAuth_Android-release.aar")

            pom {
                withXml {
                    // add dependencies to pom
                    val dependencies = asNode().appendNode("dependencies")
                    val addDependency = { dep: Dependency ->
                        if (dep.group != null &&
                            "unspecified" != dep.name &&
                            dep.version != null
                        ) {

                            val dependencyNode = dependencies.appendNode("dependency")
                            dependencyNode.appendNode("groupId", dep.group)
                            dependencyNode.appendNode("artifactId", dep.name)
                            dependencyNode.appendNode("version", dep.version)
                        }
                    }
                    configurations.implementation.get().dependencies.forEach { addDependency(it) }
                }
            }
        }
    }


    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/BonnierNews/bn-app-auth-sdk-android")

            credentials {
                val githubProperties = Properties()
                val propsFile = rootProject.file("github.properties")
                if (propsFile.canRead()) {
                    githubProperties.load(FileInputStream(propsFile))
                }
                username = (githubProperties["gpr.usr"] ?: (System.getenv("GPR_USER") ?: "")).toString()
                password = (githubProperties["gpr.key"] ?: (System.getenv("GPR_API_KEY") ?: "")).toString()
            }
        }
    }
}

tasks.named("publishReleasePublicationToMavenLocal") {
    dependsOn(":bundleReleaseAar")
}
