plugins {
	kotlin("jvm") version "2.2.20" apply false
	kotlin("plugin.spring") version "2.2.20" apply false
	id("org.springframework.boot") version "4.0.4" apply false
	id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
	group = "ru.wizard.web"
	version = "0.1.0"
}

subprojects {
	apply(plugin = "org.jetbrains.kotlin.jvm")
	apply(plugin = "org.jetbrains.kotlin.plugin.spring")
	apply(plugin = "io.spring.dependency-management")

	// Библиотечные модули не применяют boot-плагин, поэтому BOM Spring Boot
	// импортируется явно; app получает его же автоматически от boot-плагина.
	configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
		imports {
			mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.4")
		}
	}

	repositories {
		mavenCentral()
	}

	configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
		compilerOptions {
			freeCompilerArgs.addAll("-Xjsr305=strict")
		}
	}

	tasks.withType<Test>().configureEach {
		useJUnitPlatform()
	}
}
