#!/usr/bin/env bash
# Скачивает исходники vgmstream и LAME в app/src/main/cpp/third_party
set -euo pipefail

VGMSTREAM_TAG="r2168"
LAME_VERSION="3.100"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/cpp/third_party"
mkdir -p "$DEST"

# --- vgmstream ---
if [ ! -f "$DEST/vgmstream/src/libvgmstream.h" ]; then
  rm -rf "$DEST/vgmstream"
  git clone --depth 1 --branch "$VGMSTREAM_TAG" https://github.com/vgmstream/vgmstream "$DEST/vgmstream"
fi

# --- LAME (официальный архив, запасной вариант — зеркало на GitHub) ---
if [ ! -f "$DEST/lame/include/lame.h" ]; then
  rm -rf "$DEST/lame" "$DEST/lame-tmp"
  mkdir -p "$DEST/lame-tmp"
  if curl -fL --retry 3 --connect-timeout 20 -o "$DEST/lame-tmp/lame.tar.gz" \
      "https://downloads.sourceforge.net/project/lame/lame/${LAME_VERSION}/lame-${LAME_VERSION}.tar.gz" \
     && tar -xzf "$DEST/lame-tmp/lame.tar.gz" -C "$DEST/lame-tmp"; then
    mv "$DEST/lame-tmp/lame-${LAME_VERSION}" "$DEST/lame"
    echo "LAME ${LAME_VERSION}: официальный архив"
  else
    echo "SourceForge недоступен, беру зеркало на GitHub (LAME 3.99.5)"
    git clone --depth 1 https://github.com/rbrito/lame "$DEST/lame"
  fi
  rm -rf "$DEST/lame-tmp"
fi

echo "Готово:"
ls "$DEST"
