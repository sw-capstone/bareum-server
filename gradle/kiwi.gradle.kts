import java.io.File
import java.security.MessageDigest
import org.gradle.api.GradleException
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test

val kiwiVersion = "0.24.0"
// 현재 OS와 CPU에 맞는 배포 파일 선택
val os = System.getProperty("os.name").lowercase()
val arch = System.getProperty("os.arch").lowercase()
val kiwiPlatform = when {
	os.contains("mac") && arch in listOf("aarch64", "arm64") -> "mac-arm64"
	os.contains("mac") && arch in listOf("amd64", "x86_64") -> "mac-x86_64"
	os.contains("linux") && arch in listOf("aarch64", "arm64") -> "lnx-aarch64"
	os.contains("linux") && arch in listOf("amd64", "x86_64") -> "lnx-x86-64"
	os.contains("windows") && arch in listOf("amd64", "x86_64") -> "win-x64"
	else -> throw GradleException("Unsupported KiwiJava platform: $os / $arch")
}
val nativeHashes = mapOf(
	"mac-arm64" to "6ac00bb59f487eb0f82c9677a0e23cb0bc8dfb26543dedae6973ad6d95cb3e90",
	"mac-x86_64" to "f8e0a8018dc3c9e4e3c18bf63438165a06eb760e59d40eb3d144dfe8c3d7d269",
	"lnx-aarch64" to "fd456a5ae8a67f2bef8c4d8ca878dd5f56dff8b1bf1fa212ab1937a91adf1c9b",
	"lnx-x86-64" to "36b5d3ade43317880a5e9886e4f95d6c81d80bd4b71b58788af75984b7932a46",
	"win-x64" to "96826c15715167c35fe5873d98b4e23871d03d70064b471082750296cbc64ae3"
)
val modelHash = "33188ba932bba4717bad5244bbec0ef8b1c9cbb47e26e68394a7976d8d779083"
// Github에 올라온 파일을 Gradle이 다운로드할 수 있게 하는 저장소 설정 방식
repositories {
	ivy {
		name = "kiwiReleases"
		url = uri("https://github.com/bab2min/Kiwi/releases/download")
		patternLayout {
			artifact("v[revision]/kiwi-java-v[revision]-[classifier].[ext]")
			artifact("v[revision]/kiwi_model_v[revision]_[classifier].[ext]")
		}
		metadataSources { artifact() }
		content { includeGroup("kr.pe.bab2min") }
	}
}

val kiwiNative = configurations.create("kiwiNative") {
	isCanBeConsumed = false
	isTransitive = false
}
val kiwiModel = configurations.create("kiwiModel") {
	isCanBeConsumed = false
	isTransitive = false
}
// Java 라이브버리와 모델 파일 등록
dependencies {
	add("implementation", "kr.pe.bab2min:kiwi-java:$kiwiVersion:$kiwiPlatform@jar")
	add(kiwiNative.name, "kr.pe.bab2min:kiwi-java:$kiwiVersion:$kiwiPlatform@jar")
	add(kiwiModel.name, "kr.pe.bab2min:kiwi_model:$kiwiVersion:base@tgz")
}
// 파일 체크섬 검사
fun verifyKiwiHash(file: File, expected: String) {
	val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
		.joinToString("") { "%02x".format(it) }
	if (actual != expected) {
		throw GradleException("Kiwi checksum mismatch: ${file.name}")
	}
}
val verifyKiwiJava = tasks.register("verifyKiwiJava") {
	inputs.files(kiwiNative)
	inputs.property("sha256", nativeHashes.getValue(kiwiPlatform))
	doLast { verifyKiwiHash(kiwiNative.singleFile, nativeHashes.getValue(kiwiPlatform)) }
}
val verifyKiwiModel = tasks.register("verifyKiwiModel") {
	inputs.files(kiwiModel)
	inputs.property("sha256", modelHash)
	doLast { verifyKiwiHash(kiwiModel.singleFile, modelHash) }
}
// 모델 압축 풀어 build/kiwi/models/cong/base에 준비
val prepareKiwiModel = tasks.register<Sync>("prepareKiwiModel") {
	group = "build setup"
	description = "Downloads and prepares the pinned Kiwi sentence segmentation model."
	dependsOn(verifyKiwiModel)
	from({ tarTree(resources.gzip(kiwiModel.singleFile)) }) {
		include("models/cong/base/**")
	}
	into(layout.buildDirectory.dir("kiwi"))
}

tasks.named("compileJava") { dependsOn(verifyKiwiJava) }
tasks.named("bootJar") { dependsOn(prepareKiwiModel) }
tasks.named("bootRun") { dependsOn(prepareKiwiModel) }
tasks.withType<JavaExec>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }
tasks.withType<Test>().configureEach {
	dependsOn(prepareKiwiModel)
	jvmArgs("--enable-native-access=ALL-UNNAMED")
	systemProperty("kiwi.model.path", layout.buildDirectory.dir("kiwi/models/cong/base").get().asFile.absolutePath)
}
