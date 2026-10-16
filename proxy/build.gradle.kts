// Ядро проксирования: Anthropic-контроллеры, ретраи, SSE-обработка, OpenAI-совместимость.
dependencies {
	implementation(project(":model"))
	implementation(project(":service"))
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}
