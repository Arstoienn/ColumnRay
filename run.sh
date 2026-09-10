#!/usr/bin/env bash
# 編譯並執行。參數會原樣傳給 engine.Main，例如：
#   ./run.sh                                  開視窗，載入 maps/school.json
#   ./run.sh maps/school.json --shot a.png    不開視窗，輸出一張截圖
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out
javac -d out $(find src -name '*.java')
exec java -cp out engine.Main "$@"
