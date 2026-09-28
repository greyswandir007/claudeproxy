// Движок БД: доступ к SQLite/PostgreSQL, миграции, резервное копирование.
dependencies {
	implementation(project(":model"))
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
	implementation("org.xerial:sqlite-jdbc:3.50.3.0")
	runtimeOnly("org.postgresql:postgresql")
}
