// REST-контроллеры дашборда и админ-API.
dependencies {
	implementation(project(":model"))
	implementation(project(":service"))
	implementation(project(":proxy"))
	implementation(project(":database"))
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
}
