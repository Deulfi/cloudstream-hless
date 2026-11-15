# Runtime image only: CI builds engine/build/libs/daemon.jar first
# (./gradlew :engine:jar) and this file packs it.
FROM eclipse-temurin:17-jre

COPY engine/build/libs/daemon.jar /app/daemon.jar

# Engine state, installed extensions, caches and logs.
VOLUME /data
EXPOSE 7000

ENTRYPOINT ["java", \
  "--add-modules=jdk.incubator.foreign", "--enable-native-access=ALL-UNNAMED", \
  "-XX:TieredStopAtLevel=1", "-Xms16m", "-Xmx192m", "-XX:MaxDirectMemorySize=64m", \
  "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", \
  "-Dcs.cloudstream.home=/data", \
  "-cp", "/app/daemon.jar"]
# The Stremio addon launcher (docs/plans/2a). Until it exists, run the engine
# alone by overriding the command: com.lagradost.macos.MainKt <port> <token>
CMD ["com.lagradost.hless.HlessMainKt"]
