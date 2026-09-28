// Общие контракты: типы конфигурации, события, ошибки протокола.
// Модуль не содержит логики и зависит только от аннотаций Jackson и Spring Boot.
dependencies {
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.springframework.boot:spring-boot")
	implementation("org.springframework:spring-web")
	implementation("io.projectreactor:reactor-core")
}
