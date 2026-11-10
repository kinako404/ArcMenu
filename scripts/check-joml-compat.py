#!/usr/bin/env python3
"""Check that every org.joml member referenced by compiled ArcMenu classes links against each JOML version.

JOML is provided by the server (Paper 1.21.1 ships 1.10.5, 1.21.4-26.2 ship 1.10.8, 26.3 ships 1.10.9) and cannot be
shaded or relocated because Bukkit's Transformation API exposes JOML types. A reference compiled against one version
must therefore resolve in all of them; e.g. Quaternionf.dot(Quaternionf) became dot(Quaternionfc) in 1.10.9.

Usage: scripts/check-joml-compat.py [--joml VERSION|JAR ...] [CLASS_DIR|JAR ...]
Defaults: JOML 1.10.5 1.10.8 1.10.9 (resolved from ~/.m2, fetched with mvn dependency:get when missing) and
core/target/classes + paper/target/classes. Exit code 1 lists every unresolved reference.
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_VERSIONS = ["1.10.5", "1.10.8", "1.10.9"]
REF = re.compile(r"// (Method|InterfaceMethod|Field) (org/joml/[\w/$]+)\.(\"?[\w<>$]+\"?):(\S+)")
CLASS_REF = re.compile(r"// class (org/joml/[\w/$]+)")
JAVA_HOME = os.environ.get("JAVA_HOME")
JAVAP = str(Path(JAVA_HOME) / "bin" / "javap") if JAVA_HOME else "javap"


def resolve_joml(spec):
    if spec.endswith(".jar"):
        return spec, Path(spec).stem
    jar = Path.home() / ".m2/repository/org/joml/joml" / spec / f"joml-{spec}.jar"
    if not jar.is_file():
        subprocess.run(["mvn", "-B", "-q", "-ntp", "dependency:get", f"-Dartifact=org.joml:joml:{spec}"], check=True)
    return str(jar), spec


def class_files(inputs):
    tmp = None
    for item in inputs:
        path = Path(item)
        if path.is_dir():
            yield from (str(p) for p in path.rglob("*.class"))
        elif path.suffix == ".jar":
            tmp = tmp or tempfile.mkdtemp(prefix="joml-compat-")
            with zipfile.ZipFile(path) as archive:
                for name in archive.namelist():
                    if name.endswith(".class") and name.startswith("com/fentai/"):
                        archive.extract(name, tmp)
                        yield os.path.join(tmp, name)


def collect_refs(files):
    refs, classes = {}, set()
    for start in range(0, len(files), 200):
        batch = files[start:start + 200]
        out = subprocess.run([JAVAP, "-c", "-p"] + batch, capture_output=True, text=True, check=True).stdout
        owner = None
        for line in out.splitlines():
            if line.startswith("Compiled from") or re.match(r"^\S.*(class|interface) ", line):
                owner = line
            for kind, cls, name, desc in REF.findall(line):
                refs.setdefault((kind, cls, name.strip('"'), desc), set()).add(owner_name(owner))
            classes.update(CLASS_REF.findall(line))
    return refs, classes


def owner_name(header):
    if not header:
        return "?"
    match = re.search(r"(?:class|interface) ([\w.$]+)", header)
    return match.group(1) if match else header


class Members:
    def __init__(self, jar):
        self.jar = jar
        self.cache = {}

    def load(self, cls):
        if cls in self.cache:
            return self.cache[cls]
        result = subprocess.run([JAVAP, "-s", "-p", "-cp", self.jar, cls.replace("/", ".")], capture_output=True, text=True)
        if result.returncode != 0:
            self.cache[cls] = None
            return None
        lines = result.stdout.splitlines()
        supers, members = [], set()
        simple = cls.split("/")[-1]
        for index, line in enumerate(lines):
            header = re.match(r"^\S.*?(?:class|interface) [\w.$]+(?:<.*?>)?(.*)\{$", line)
            if header:
                rest = re.sub(r"<[^<>]*(<[^<>]*>[^<>]*)*>", "", header.group(1))
                for part in re.findall(r"(?:extends|implements) ([\w.$, ]+)", rest):
                    supers += [s.strip().replace(".", "/") for s in part.split(",") if s.strip()]
            descriptor = re.match(r"^\s+descriptor: (\S+)", line)
            if descriptor and index > 0:
                decl = re.sub(r"<[^<>]*(<[^<>]*>[^<>]*)*>", "", lines[index - 1]).strip()
                if "(" in decl:
                    name = decl.split("(")[0].split()[-1]
                    name = "<init>" if name.split(".")[-1] == simple else name
                else:
                    name = decl.rstrip(";").split()[-1].split("=")[0].strip()
                members.add((name, descriptor.group(1)))
        self.cache[cls] = (supers, members)
        return self.cache[cls]

    def has(self, cls, name, desc, seen=None):
        seen = seen or set()
        if cls in seen:
            return False
        seen.add(cls)
        entry = self.load(cls)
        if entry is None:
            return False
        supers, members = entry
        if (name, desc) in members:
            return True
        if name == "<init>":
            return False
        return any(self.has(s, name, desc, seen) for s in supers + ["java/lang/Object"] if s != cls)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--joml", action="append", help="JOML version or jar (repeatable)")
    parser.add_argument("inputs", nargs="*", help="class directories or plugin jars")
    args = parser.parse_args()
    inputs = args.inputs or [str(ROOT / "core/target/classes"), str(ROOT / "paper/target/classes")]
    files = sorted(class_files(inputs))
    if not files:
        sys.exit(f"no class files under {inputs}; build first")
    refs, classes = collect_refs(files)
    print(f"{len(files)} classes, {len(refs)} distinct org.joml member references, {len(classes)} org.joml classes", flush=True)
    failed = False
    for spec in args.joml or DEFAULT_VERSIONS:
        jar, label = resolve_joml(spec)
        members = Members(jar)
        missing = [f"class {c}" for c in sorted(classes) if members.load(c) is None]
        for (kind, cls, name, desc), users in sorted(refs.items()):
            if not members.has(cls, name, desc):
                missing.append(f"{kind} {cls}.{name}:{desc}  <- {', '.join(sorted(users))}")
        print(f"JOML {label}: {'OK' if not missing else f'{len(missing)} unresolved'}")
        for entry in missing:
            print(f"  {entry}")
        failed = failed or bool(missing)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
