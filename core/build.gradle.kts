plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.ibm.icu:icu4j:77.1")
    testImplementation("junit:junit:4.13.2")
}
