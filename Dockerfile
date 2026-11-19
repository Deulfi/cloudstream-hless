# Runtime image only: CI builds engine/build/libs/daemon.jar first
# (./gradlew :engine:jar) and this file packs it.
FROM eclipse-temurin:17-jre

COPY engine/build/libs/daemon.jar /app/daemon.jar
COPY scripts/hless-entrypoint.sh /app/entrypoint.sh

# Engine state, installed extensions, caches and logs.
VOLUME /data
EXPOSE 7000

ENTRYPOINT ["sh", "/app/entrypoint.sh"]
# The Stremio addon launcher. To run the engine alone, override the command:
# com.lagradost.macos.MainKt <port> <token>
CMD ["com.lagradost.hless.HlessMainKt"]
