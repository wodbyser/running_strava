# Build: Gradle with JDK 23 (the project's target).
FROM eclipse-temurin:23-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY buildSrc buildSrc
COPY core/core.gradle.kts core/
COPY runner/runner.gradle.kts runner/
RUN ./gradlew --no-daemon -q help
COPY core core
COPY runner runner
RUN ./gradlew --no-daemon :runner:bootJar

# Extract the jar and record a class-data-sharing archive: noticeably faster start-up on a small CPU.
# The training run uses in-memory H2 so it needs no real database.
FROM eclipse-temurin:23-jre AS cds
WORKDIR /app
COPY --from=build /src/runner/build/libs/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination extracted \
 && cd extracted \
 && java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh -jar app.jar \
      --spring.datasource.url=jdbc:h2:mem:train --spring.datasource.driver-class-name=org.h2.Driver \
      --spring.datasource.username=sa --spring.datasource.password= \
      --spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect

FROM eclipse-temurin:23-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=cds --chown=app /app/extracted ./
USER app
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:SharedArchiveFile=app.jsa -Xlog:cds=off -Xlog:cds+dynamic=off"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
