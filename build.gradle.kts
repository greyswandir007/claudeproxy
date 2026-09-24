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
val buildDashboard = tasks.register<Exec>("buildDashboard") {
	workingDir = file("web")
	commandLine(
		if (System.getProperty("os.name").lowercase().contains("windows")) {
			listOf("cmd", "/c", "npm", "install", "&&", "npm", "run", "build")
		} else {
			listOf("sh", "-c", "npm install && npm run build")
		},
	)
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
	from("web/dist")
	into(layout.buildDirectory.dir("resources/main/static"))
	onlyIf { file("web/dist").exists() }
	mustRunAfter(tasks.named("processResources"))
}

tasks.named("bootJar") { dependsOn(copyDashboardIntoJar) }
tasks.named("jar") { dependsOn(copyDashboardIntoJar) }
tasks.named("resolveMainClassName") { dependsOn(copyDashboardIntoJar) }
