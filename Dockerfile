# Etapa 1: compila con el wrapper de Maven (la EC2 no necesita tener Java ni Maven instalados)
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

# Etapa 2: solo el JRE y el jar, con un usuario sin privilegios
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --no-create-home gymflow
COPY --from=build /src/target/ms-gymflow-mq-admin-*.jar app.jar
USER gymflow
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
