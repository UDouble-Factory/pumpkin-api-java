#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
: "${GROUP:?}" "${ARTIFACT:?}" "${VERSION:?}"

build_dir="$PWD/build/jitpack"
mkdir -p "$build_dir"

wit_revision=$(git rev-parse HEAD:wit)
wit_url=$(git config -f .gitmodules --get submodule.wit.url)
wit_checkout=$(mktemp -d "$build_dir/wit.XXXXXX")
git -C "$wit_checkout" init
git -C "$wit_checkout" fetch --depth 1 "$wit_url" "$wit_revision"
git -C "$wit_checkout" checkout --detach FETCH_HEAD
if [ -e wit ]; then
    mv wit "$wit_checkout.previous"
fi
mv "$wit_checkout" wit

export RUSTUP_HOME="$build_dir/rustup"
export CARGO_HOME="$build_dir/cargo"
export CARGO_TARGET_DIR="$build_dir/cargo-target"
curl -LsSf https://sh.rustup.rs -o "$build_dir/install-rust.sh"
sh "$build_dir/install-rust.sh" -y --no-modify-path --profile minimal --default-toolchain 1.88.0

wit_bindgen_version=$(sed -n 's/^witBindgenVersion=//p' gradle/tool-versions.properties | tr -d '\r')
wasm_tools_version=$(sed -n 's/^wasmToolsVersion=//p' gradle/tool-versions.properties | tr -d '\r')
"$CARGO_HOME/bin/cargo" install wit-bindgen-cli --version "$wit_bindgen_version" --locked --no-default-features --features c,async --jobs 2 --root "$build_dir/wit-bindgen"
export WIT_BINDGEN="$build_dir/wit-bindgen/bin/wit-bindgen"

"$CARGO_HOME/bin/cargo" install wasm-tools --version "$wasm_tools_version" --locked --no-default-features --features component --jobs 2 --root "$build_dir/wasm-tools"
export WASM_TOOLS="$build_dir/wasm-tools/bin/wasm-tools"

"$WIT_BINDGEN" --version
"$WASM_TOOLS" --version
./gradlew build :api:publishToMavenLocal :gradle-plugin:publishToMavenLocal \
    "-PpumpkinMavenGroup=$GROUP.$ARTIFACT" "-Ppumpkin_api_version=$VERSION" --no-daemon
