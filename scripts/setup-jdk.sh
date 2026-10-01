#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
if command -v javac >/dev/null; then javac -version; exit 0; fi
[[ "$(uname -s)-$(uname -m)" == Linux-x86_64 ]] || blocked 'Install JDK 21 and set JAVA_HOME on this platform.'
mkdir -p .tools/downloads .tools/jdk
curl -fsSL --retry 2 --connect-timeout 20 \
  'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz' \
  -o .tools/downloads/jdk.tar.gz
printf '%s  %s\n' ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94 .tools/downloads/jdk.tar.gz | sha256sum -c -
tar -xzf .tools/downloads/jdk.tar.gz -C .tools/jdk --strip-components=1
.tools/jdk/bin/javac -version
