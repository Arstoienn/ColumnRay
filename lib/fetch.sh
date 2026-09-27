#!/usr/bin/env bash
# Fetch the one dependency this engine has: LWJGL, which is the window and the GL entry points.
#
# The jars are not committed. They are ~4 MB a platform of binary that Maven Central already
# hosts and that nobody would ever read a diff of, so what is tracked here is the version and the
# checksum of every file, and this fetches what they name. A jar whose hash does not match is
# deleted rather than used: that is the whole reason this is a script and not a curl in the README.
#
# Only the natives for the machine that runs it are fetched. The table has every platform CI
# builds on, so a checksum is still a tracked fact for a machine this one is not.
#
#   ./lib/fetch.sh            fetch what is missing
#   ./lib/fetch.sh --check    verify what is here and fetch nothing
set -euo pipefail
cd "$(dirname "$0")"

VERSION=3.4.3
BASE=https://repo1.maven.org/maven2/org/lwjgl
MODULES="lwjgl lwjgl-glfw lwjgl-opengl"

# Every file this project will ever ask for, as "name sha256".
read -r -d '' SUMS <<'EOF' || true
lwjgl-3.4.3.jar 46eeca5471833c3cf5da3c1da015b41e3bb3eb16dd3da03f366886da10096751
lwjgl-glfw-3.4.3.jar 2a60dbe93711632129a2ce6aa7e073aa58fa5450a88cde48a6af0a4dfeed606d
lwjgl-opengl-3.4.3.jar a92cf30f25b6fb6066f3508511f83c5c4fe2a0cff14fc2e7373bc1f6c726374f
lwjgl-3.4.3-natives-macos-arm64.jar 0cf1eac3c360395846320ec47a5e0da313fbb5ee92414ce09ba7e7cc11274509
lwjgl-glfw-3.4.3-natives-macos-arm64.jar 0ded43fd421c832e7ec6fbb4f63b6860c3760fa4385d58e2a17778e59a951ace
lwjgl-opengl-3.4.3-natives-macos-arm64.jar 13aafa148f6f2d81403f1eb0635691443a5f463db7c5655d65c83cbdadd6268d
lwjgl-3.4.3-natives-macos.jar 77422addd5bedbbe9679b5b37b18d942233f2c22c5b34dac9143d8f3d4bc11f2
lwjgl-glfw-3.4.3-natives-macos.jar 8ac6cda49aa08b528452fa45c71ed49762a83b1924b2cb1278065b463c236fe1
lwjgl-opengl-3.4.3-natives-macos.jar bca6ab5d50b483a571b8070f7a08177d487c502ec6863279754206d93a643e6b
lwjgl-3.4.3-natives-linux.jar d719e545a6db4ea87eb64d55630ba628755572bb5b56d51a1548347b5354cadb
lwjgl-glfw-3.4.3-natives-linux.jar aa25296d8fc96355acf0b69eb39083e98c881a204950a708a0c47ab1be34a5d4
lwjgl-opengl-3.4.3-natives-linux.jar 4056e81c1174201f63e980f76b9955746c0f13d712e4d30ee02c38ae6bccd4ce
lwjgl-3.4.3-natives-linux-arm64.jar 80a500dfb39fdea719a6bb2458235756e9b1e488fcbd7345aee9fd2cdaced6ff
lwjgl-glfw-3.4.3-natives-linux-arm64.jar df031b5b97bcb589f8bbd66714c752460ed9965caf659916be69c44138c32ee5
lwjgl-opengl-3.4.3-natives-linux-arm64.jar 07f6f7f69cac519ac0506fd264479020675b03ef59c1f4671e4d8615ab500d43
lwjgl-3.4.3-natives-windows.jar 19949bca7b780f55e5d2db12ac061a7657a4c1b7c860c30607f5406e7017aa2b
lwjgl-glfw-3.4.3-natives-windows.jar de2826d6bc8e73127b42250a282cbfcf91b3c3435edf7f9fd16afcabc25e88ec
lwjgl-opengl-3.4.3-natives-windows.jar 0e082b9203ca4112c740735789cc4dd9dcaf2032212b64ba9089ac7dca035a40
EOF

case "$(uname -s)" in
    Darwin)  [ "$(uname -m)" = arm64 ] && CLASSIFIER=natives-macos-arm64 || CLASSIFIER=natives-macos ;;
    Linux)   [ "$(uname -m)" = aarch64 ] && CLASSIFIER=natives-linux-arm64 || CLASSIFIER=natives-linux ;;
    MINGW*|MSYS*|CYGWIN*) CLASSIFIER=natives-windows ;;
    *) echo "lib/fetch.sh: no LWJGL natives listed for $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac

want() { echo "$SUMS" | awk -v f="$1" '$1 == f { print $2 }'; }

sha() {
    if command -v shasum > /dev/null; then shasum -a 256 "$1" | cut -d' ' -f1
    else sha256sum "$1" | cut -d' ' -f1; fi
}

status=0
for module in $MODULES; do
    for file in "$module-$VERSION.jar" "$module-$VERSION-$CLASSIFIER.jar"; do
        expected=$(want "$file")
        if [ -z "$expected" ]; then echo "lib/fetch.sh: no checksum listed for $file" >&2; exit 1; fi
        if [ -f "$file" ] && [ "$(sha "$file")" = "$expected" ]; then continue; fi
        if [ "${1:-}" = "--check" ]; then
            echo "missing or altered: $file"
            status=1
            continue
        fi
        rm -f "$file"
        echo "fetching $file"
        curl -sSfL -o "$file" "$BASE/$module/$VERSION/$file"
        got=$(sha "$file")
        if [ "$got" != "$expected" ]; then
            rm -f "$file"
            echo "lib/fetch.sh: $file hashed $got, expected $expected - deleted" >&2
            exit 1
        fi
    done
done
exit $status
