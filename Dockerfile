# Runtime image only: CI builds engine/build/libs/daemon.jar first
# (./gradlew :engine:jar) and this file packs it.
FROM eclipse-temurin:17-jre

COPY engine/build/libs/daemon.jar /app/daemon.jar

# Engine state, installed extensions, caches and logs.
VOLUME /data
EXPOSE 7000

ENTRYPOINT ["java", \
  "--add-modules=jdk.incubator.foreign", "--enable-native-access=ALL-UNNAMED", \
  "-XX:TieredStopAtLevel=1", "-Xms16m", "-Xmx384m", "-XX:MaxDirectMemorySize=96m", \
  "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-Dsun.net.inetaddr.ttl=600", "-Djava.awt.headless=true", \
  "-Dcs.cloudstream.home=/data", \
  "-cp", "/app/daemon.jar"]
# The Stremio addon launcher. To run the engine alone, override the command:
# com.lagradost.macos.MainKt <port> <token>
CMD ["com.lagradost.hless.HlessMainKt"]
