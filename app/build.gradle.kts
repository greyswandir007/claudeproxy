import java.io.File

plugins {
	kotlin("jvm")
	kotlin("plugin.spring")
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}

base {
	archivesName.set("claudeproxy")
}

dependencies {
	implementation(project(":model"))
	implementation(project(":database"))
	implementation(project(":service"))
	implementation(project(":proxy"))
	implementation(project(":api"))

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

// Сборка веб-дашборда: npm-скрипт в каталоге web/ репозитория, результат
// копируется в static/ внутри jar.
val webDashboardDir = rootProject.layout.projectDirectory.dir("web")

// В git-bash на Windows npm доступен как npm.cmd; ищем его в PATH.
val npmCommand: String? = if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
	listOf("npm.cmd", "npm").firstOrNull { candidate ->
		val pathEnv = System.getenv("PATH") ?: ""
		pathEnv.split(File.pathSeparator).any { dir ->
			File(dir, candidate).isFile
		}
	}
} else {
	"npm"
}

val installDashboardDependencies = tasks.register<Exec>("installDashboardDependencies") {
	group = "build"
	description = "Установка зависимостей дашборда (npm install)"
	onlyIf { npmCommand != null }
	workingDir = webDashboardDir.asFile
	args("install", "--no-audit", "--no-fund")
	inputs.file(webDashboardDir.file("package.json"))
	inputs.file(webDashboardDir.file("package-lock.json"))
	outputs.file(webDashboardDir.file("node_modules/.package-lock.json"))
	doFirst { executable = npmCommand!! }
}

val buildDashboard = tasks.register<Exec>("buildDashboard") {
	group = "build"
	description = "Сборка веб-дашборда (npm run build) в web/dist"
	dependsOn(installDashboardDependencies)
	onlyIf { npmCommand != null }
	workingDir = webDashboardDir.asFile
	args("run", "build")
	inputs.dir(webDashboardDir.dir("src"))
	inputs.file(webDashboardDir.file("package.json"))
	outputs.dir(webDashboardDir.dir("dist"))
	doFirst { executable = npmCommand!! }
}

val copyDashboardIntoJar = tasks.register<Copy>("copyDashboardIntoJar") {
	group = "build"
	description = "Копирует собранный дашборд в static/ внутри jar"
	dependsOn(buildDashboard)
	from(webDashboardDir.dir("dist"))
	into(layout.buildDirectory.dir("resources/main/static"))
	mustRunAfter(tasks.named("processResources"))
	onlyIf { webDashboardDir.dir("dist").asFile.exists() }
}

tasks.named("jar") { dependsOn(copyDashboardIntoJar) }
tasks.named("bootJar") { dependsOn(copyDashboardIntoJar) }
tasks.named("resolveMainClassName") { dependsOn(copyDashboardIntoJar) }
