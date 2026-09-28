import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test

val checkDuplicateFlywayMigrationVersions = tasks.register("checkDuplicateFlywayMigrationVersions") {
	group = "verification"
	description = "Checks Flyway migration filenames and duplicate versions."
	val migrations = fileTree("src/main/resources/db/migration") { include("V*.sql") }
	inputs.files(migrations)
	doLast {
		val pattern = Regex("""^V(\d{4}\.\d{2}\.\d{2}\.\d{2}\.\d{2})__[a-z][a-z0-9]*(?:_[a-z0-9]+)*\.sql$""")
		val duplicates = migrations.files.groupBy { file ->
			pattern.matchEntire(file.name)?.groupValues?.get(1)
				?: throw GradleException("Invalid Flyway migration filename: ${file.name}")
		}.filterValues { it.size > 1 }
		if (duplicates.isNotEmpty()) {
			throw GradleException("Duplicate Flyway migration versions: " +
				duplicates.entries.joinToString { (version, files) ->
					"$version (${files.map { it.name }.sorted().joinToString()})"
				})
		}
	}
}

tasks.named<Test>("test") {
	dependsOn(checkDuplicateFlywayMigrationVersions)
	useJUnitPlatform()
}
