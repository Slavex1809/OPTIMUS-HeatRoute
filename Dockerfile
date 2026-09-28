FROM maven:3.9.9-eclipse-temurin-11 AS build
WORKDIR /build
COPY backend/pom.xml ./pom.xml
COPY backend/src ./src
RUN mvn -B -ntp -DargLine=-Xmx3g clean verify

FROM eclipse-temurin:11-jre-jammy
RUN groupadd --gid 10001 optimus && useradd --uid 10001 --gid optimus --no-create-home optimus && mkdir /data && chown optimus:optimus /data
WORKDIR /app
COPY --from=build /build/target/optimus-heatroute-2.1.0.jar /app/optimus.jar
COPY scripts/healthcheck.sh /app/healthcheck.sh
RUN chmod 755 /app/healthcheck.sh
USER optimus
ENV OPTIMUS_STORAGE=/data JAVA_TOOL_OPTIONS="-Xms256m -Xmx6g -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 CMD ["/app/healthcheck.sh"]
ENTRYPOINT ["java","-jar","/app/optimus.jar"]
