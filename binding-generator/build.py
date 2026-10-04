import argparse
import json
import pathlib
import shutil
import subprocess

from generate import Generator
from tools import executable


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wit", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--cache", required=True, type=pathlib.Path)
    parser.add_argument("--versions", required=True, type=pathlib.Path)
    args = parser.parse_args()
    versions = dict(line.split("=", 1) for line in args.versions.read_text().splitlines() if line and not line.startswith("#"))
    bindgen = executable("wit-bindgen", versions["witBindgenVersion"], args.cache)
    wasm_tools = executable("wasm-tools", versions["wasmToolsVersion"], args.cache)
    generated = args.output.resolve()
    native = generated / "native"
    java = (generated / "java").resolve()
    if not java.is_relative_to(generated):
        raise ValueError("Generated path escapes output directory")
    if java.exists():
        shutil.rmtree(java)
    native.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(bindgen), "c", str(args.wit), "--string-encoding", "utf16", "--no-sig-flattening", "--out-dir", str(native)], check=True)
    schema_file = generated / "schema.json"
    subprocess.run([str(wasm_tools), "component", "wit", str(args.wit), "--json", "-o", str(schema_file)], check=True)
    schema = json.loads(schema_file.read_text(encoding="utf-8"))
    generator = Generator(schema, (native / "plugin.h").read_text(encoding="utf-8"), generated)
    generator.generate()
    manifest = json.loads((generated / "manifest.json").read_text())
    print(f"Generated {manifest['imports']} imports, {manifest['exports']} exports, {manifest['resources']} resource types")


if __name__ == "__main__":
    main()
