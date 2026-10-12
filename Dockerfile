# Multi-stage build: compile with Maven, run on a slim JRE.
# Build the production image (with Pgvector support):
#   docker build --build-arg MAVEN_PROFILE=pgvector -t repo-intel-rag .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -e -B dependency:go-offline
COPY scripts ./scripts
RUN bash scripts/download-model.sh
COPY src ./src
ARG MAVEN_PROFILE
RUN if [ -n "$MAVEN_PROFILE" ]; then \
			mvn -q -B -P"$MAVEN_PROFILE" clean package -DskipTests; \
		else \
			mvn -q -B clean package -DskipTests; \
		fi

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /app/target/repo-intel-rag-*.jar app.jar
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=default
ENTRYPOINT ["java", "-jar", "app.jar"]
