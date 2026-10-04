# Образ игрового сервера «Горячая картошка»: Paper + плагин + готовая арена.
#
# Двухэтапная сборка: на первом этапе Gradle собирает и тестирует плагин,
# во второй образ попадает только готовый jar. Для сборки не нужны ни Java, ни Gradle на машине.

# ---------------------------------------------------------------- сборка плагина
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Сначала только файлы сборки: слой с зависимостями кэшируется,
# пока не поменялся build.gradle.kts.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src src
RUN ./gradlew --no-daemon build

# ---------------------------------------------------------------- сервер
# itzg/minecraft-server — стандартный образ для Minecraft-серверов:
# сам скачивает Paper нужной версии, корректно останавливается по SIGTERM
# и содержит проверку здоровья mc-health.
FROM itzg/minecraft-server:java21

# Всё из /plugins при старте синхронизируется в /data/plugins.
COPY --from=build /src/build/libs/*.jar /plugins/
COPY deploy/server/arenas.yml /plugins/HotPotato/arenas.yml

# Сервер под одну мини-игру: плоский мир без мобов, структур и ада.
ENV TYPE=PAPER \
    VERSION=1.21.4 \
    LEVEL_TYPE=FLAT \
    MODE=adventure \
    DIFFICULTY=peaceful \
    SPAWN_PROTECTION=0 \
    ALLOW_NETHER=false \
    GENERATE_STRUCTURES=false \
    SPAWN_MONSTERS=false \
    SPAWN_ANIMALS=false \
    SPAWN_NPCS=false \
    VIEW_DISTANCE=6 \
    MOTD="Горячая картошка" \
    HOTPOTATO_AUTO_JOIN_ARENA=main
