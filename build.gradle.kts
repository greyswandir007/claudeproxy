import java.io.File

plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "ru.wizard.web"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.xerial:sqlite-jdbc:3.50.3.0")
	implementation("net.logstash.logback:logstash-logback-encoder:8.1")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("io.projectreactor:reactor-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Дашборд: сборка (npm) и встраивание web/dist в jar как classpath:/static/.
// npm определяется по PATH: без Node.js buildDashboard пропускается (onlyIf),
// а jar просто не получает дашборд, если web/dist не собран.
val isWindowsBuild = System.getProperty("os.name").lowercase().contains("windows")
val npmAvailable = run {
	val pathDirectories: List<String> =
		System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
	val npmFileNames = if (isWindowsBuild) listOf("npm.cmd", "npm.exe") else listOf("npm")
	pathDirectories.any { directory ->
		npmFileNames.any { fileName -> File(directory, fileName).isFile }
	}
}

val buildDashboard = tasks.register<Exec>("buildDashboard") {
	workingDir = file("web")
	commandLine(
		if (isWindowsBuild) {
			listOf("cmd", "/c", "npm", "install", "&&", "npm", "run", "build")
		} else {
			listOf("sh", "-c", "npm install && npm run build")
		},
	)
	onlyIf { npmAvailable }
	inputs.dir("web/src")
	inputs.files(
		"web/package.json",
		"web/package-lock.json",
		"web/index.html",
		"web/vite.config.ts",
		"web/tsconfig.json",
	)
	outputs.dir("web/dist")
}

val copyDashboardIntoJar = tasks.register<Copy>("copyDashboardIntoJar") {
	dependsOn(buildDashboard)
	from("web/dist")
	into(layout.buildDirectory.dir("resources/main/static"))
	onlyIf { file("web/dist").exists() }
	mustRunAfter(tasks.named("processResources"))
}

tasks.named("bootJar") { dependsOn(copyDashboardIntoJar) }
tasks.named("jar") { dependsOn(copyDashboardIntoJar) }
tasks.named("resolveMainClassName") { dependsOn(copyDashboardIntoJar) }
