import os
import pathlib
import platform
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile


def executable(name, version, cache):
    override = os.environ.get(name.upper().replace("-", "_"))
    if override:
        path = pathlib.Path(override)
    else:
        system = platform.system()
        operating_system = {"Windows": "windows", "Linux": "linux", "Darwin": "macos"}[system]
        arch = "aarch64" if platform.machine().lower() in ("aarch64", "arm64") else "x86_64"
        suffix = ".exe" if system == "Windows" else ""
        directory = cache / name / version
        path = directory / "bin" / (name + suffix)
        if not path.is_file():
            extension = "zip" if system == "Windows" else "tar.gz"
            filename = f"{name}-{version}-{arch}-{operating_system}.{extension}"
            url = f"https://github.com/bytecodealliance/{name}/releases/download/v{version}/{filename}"
            directory.mkdir(parents=True, exist_ok=True)
            archive = directory / filename
            partial = directory / (filename + ".part")
            if not archive.exists():
                urllib.request.urlretrieve(url, partial)
                partial.replace(archive)
            unpacked = directory / "unpacked"
            unpacked.mkdir(exist_ok=True)
            if extension == "zip":
                with zipfile.ZipFile(archive) as package:
                    for member in package.infolist():
                        target = (unpacked / member.filename).resolve()
                        if not target.is_relative_to(unpacked.resolve()):
                            raise ValueError("Archive entry escapes tool directory")
                    package.extractall(unpacked)
            else:
                with tarfile.open(archive) as package:
                    package.extractall(unpacked, filter="data")
            source = next(unpacked.rglob(name + suffix))
            path.parent.mkdir(exist_ok=True)
            shutil.copy2(source, path)
            path.chmod(0o755)
    actual = subprocess.check_output([str(path), "--version"], text=True).strip()
    reported_name = "wit-bindgen-cli" if name == "wit-bindgen" else name
    if not actual.startswith(f"{reported_name} {version}"):
        raise ValueError(f"Expected {name} {version}, got {actual}")
    return path
