# SPDX-License-Identifier: Apache-2.0
# Charon image. Built from the Gradle application distribution (`:services:charon:installDist`), so the
# build context is the kotlin/ subtree root. Charon bakes no data — it moves it (Seaweed/Redis/DB) at
# runtime. The launcher carries the Arrow `--add-opens` (applicationDefaultJvmArgs) so moves work in-image.
#
#   docker build -f services/charon/deploy/Dockerfile -t charon:0.1.0-local .
# BASE_IMAGE is overridable for offline/local builds (e.g. a JDK image already in the engine).
ARG BASE_IMAGE=eclipse-temurin:21-jre
FROM ${BASE_IMAGE}

# The launcher uses $JAVA_HOME/bin/java when JAVA_HOME is set, else `java` on PATH. Clear it so a base
# image with a stale JAVA_HOME (some JDK dev images) falls back to PATH java; temurin has java on PATH.
ENV JAVA_HOME=""

WORKDIR /opt/charon
COPY services/charon/build/install/charon/ /opt/charon/

# 7250 = HTTP probes + /metrics (Prometheus scrape, inline micrometer — the house observability pattern);
# 7251 = the CharonService gRPC surface (Materialize/Stage/Copy/Evict/Describe).
EXPOSE 7250 7251
ENTRYPOINT ["/opt/charon/bin/charon"]
