import com.diffplug.gradle.spotless.JavaExtension
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.diffplug.spotless") version "8.10.4"
}

group = "com.bareum"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(25))
		vendor.set(JvmVendorSpec.ADOPTIUM)
	}
}

repositories {
	mavenCentral()
}

spotless {
	lineEndings = com.diffplug.spotless.LineEnding.UNIX
	java {
		target("src/main/java/**/*.java")
		targetExclude("**/generated/**", "**/build/**")
		removeUnusedImports()
		importOrder("\\#", "java", "javax", "org", "net", "com", "")
		eclipse("4.41").configFile("config/formatter/naver-eclipse-formatter.xml")
		trimTrailingWhitespace()
		endWithNewline()
	}
	format("javaTest", JavaExtension::class.java) {
		target("src/test/java/**/*.java")
		targetExclude("**/generated/**", "**/build/**")
		removeUnusedImports()
		importOrder("\\#", "java", "javax", "org", "net", "com", "")
		eclipse("4.41")
			.configFile("config/formatter/naver-eclipse-formatter.xml")
			.configProperties(
				"""
				org.eclipse.jdt.core.formatter.insert_new_line_after_opening_brace_in_array_initializer=do not insert
				org.eclipse.jdt.core.formatter.insert_new_line_before_closing_brace_in_array_initializer=do not insert
				""".trimIndent()
			)
		trimTrailingWhitespace()
		endWithNewline()
	}
}

dependencies {
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")
	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")

	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	implementation("org.postgresql:postgresql")

	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.security:spring-security-oauth2-jose")
	implementation("org.bouncycastle:bcprov-jdk18on:1.86")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-scalar:3.1.1")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<BootJar>("bootJar") {
	archiveFileName.set("app.jar")
}

apply(from = "gradle/testing.gradle.kts")
apply(from = "gradle/kiwi.gradle.kts")
