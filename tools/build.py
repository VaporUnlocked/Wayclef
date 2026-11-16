#!/usr/bin/env python3
"""Compile Wayclef against a local Minecraft 26.3 and Meteor installation."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.9.0-beta.7"


def jdk_tool(jdk, name):
    return jdk.resolve() / "bin" / (name + (".exe" if os.name == "nt" else ""))


def compile_java(javac, source_root, classpath, output):
    output.mkdir()
    arguments = ["--release", "25", "-proc:none", "-encoding", "UTF-8", "-d", str(output)]
    if classpath:
        arguments.extend(["-classpath", os.pathsep.join(map(str, classpath))])
    arguments.extend(str(path) for path in sorted(source_root.rglob("*.java")))
    argument_file = output.parent / (output.name + ".javac.args")
    write_arguments(argument_file, arguments)
    subprocess.run([str(javac), "@" + str(argument_file)], check=True)


def write_arguments(path, arguments):
    quoted = ['"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"' for value in arguments]
    path.write_text("\n".join(quoted) + "\n", encoding="utf-8")


def extract_meteor_dependencies(meteor, staging):
    nested = []
    with zipfile.ZipFile(meteor) as archive:
        meta = json.loads(archive.read("fabric.mod.json"))
        if meta.get("id") != "meteor-client" or meta.get("version") != "26.3-17":
            raise ValueError("Expected the Meteor Client 26.3-17 JAR.")
        for entry in archive.infolist():
            path = PurePosixPath(entry.filename)
            if path.parts[:2] != ("META-INF", "jars") or len(path.parts) != 3 or path.suffix != ".jar":
                continue
            destination = staging / path.name
            destination.write_bytes(archive.read(entry))
            nested.append(destination)
    return nested


def package(classes, destination, resource_modules):
    entries = {}
    for item in classes.rglob("*.class"):
        name = item.relative_to(classes).as_posix()
        if not name.startswith("dev/soprano/"):
            raise ValueError(f"Unexpected class in build output: {name}")
        entries[name] = item.read_bytes()
    resources = ROOT / "src/main/resources"
    for item in resources.rglob("*"):
        if item.is_file():
            entries[item.relative_to(resources).as_posix()] = item.read_bytes()
    metadata = json.loads(entries["fabric.mod.json"])
    metadata["version"] = VERSION
    metadata["jars"] = []
    for module in resource_modules:
        name = f"META-INF/jars/{module.name}"
        entries[name] = module.read_bytes()
        metadata["jars"].append({"file": name})
    entries["fabric.mod.json"] = (json.dumps(metadata, indent=2) + "\n").encode("utf-8")
    with zipfile.ZipFile(destination, "w") as jar:
        for name in sorted(entries):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            jar.writestr(entry, entries[name], compress_type=zipfile.ZIP_DEFLATED)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--minecraft", type=Path, required=True, help="Minecraft 26.3 client JAR")
    parser.add_argument("--meteor", type=Path, required=True, help="Meteor Client 26.3-17 JAR")
    parser.add_argument("--libraries", type=Path, action="append", required=True, help="Local game and Fabric library roots")
    parser.add_argument("--test", action="store_true")
    parser.add_argument("--test-gpu", action="store_true", help="Require a dedicated Vulkan device for GPU integration tests")
    args = parser.parse_args()

    with zipfile.ZipFile(args.minecraft) as archive:
        game_version = json.loads(archive.read("version.json")).get("id")
        if game_version != "26.3":
            parser.error("Minecraft 26.3 is required.")
    destination = ROOT / "all .jar's go here" / f"wayclef-{VERSION}-mc26.3.jar"
    destination.parent.mkdir(exist_ok=True)
    libraries = sorted({jar.resolve() for root in args.libraries for jar in root.rglob("*.jar") if "-natives-" not in jar.name})
    with tempfile.TemporaryDirectory(prefix="wayclef-compile-") as scratch:
        staging = Path(scratch)
        meteor_dependencies = extract_meteor_dependencies(args.meteor, staging)
        resource_modules = [jar for jar in meteor_dependencies
                            if jar.name.startswith(("fabric-api-base-", "fabric-resource-loader-v1-"))]
        if len(resource_modules) != 2:
            parser.error("The pinned Meteor distribution must include the two Fabric resource modules for standalone fonts.")
        classpath = [args.minecraft.resolve(), args.meteor.resolve(), *meteor_dependencies, *libraries]
        classes = staging / "main"
        compiler = jdk_tool(args.jdk, "javac")
        compile_java(compiler, ROOT / "src/main/java", classpath, classes)
        if args.test or args.test_gpu:
            tests = staging / "test"
            compile_java(compiler, ROOT / "src/test/java", [classes, *classpath], tests)
            test_classpath = os.pathsep.join(map(str, [tests, classes, *classpath]))
            test_arguments = staging / "tests.java.args"
            for suite in (
                "dev.soprano.path.IncrementalSearchTest",
                "dev.soprano.path.RouteGoalPolicyTest",
                "dev.soprano.map.VoxelRendererTest",
                "dev.soprano.map.MapSnapshotTest",
                "dev.soprano.map.MapFeaturesTest",
                "dev.soprano.map.PortalTrackerTest",
                "dev.soprano.map.PortalLinksTest",
                "dev.soprano.game.EndPortalAutomationTest",
                "dev.soprano.game.NetherFrameTest",
                "dev.soprano.map.SurveyCacheTest",
                "dev.soprano.map.GpuSurveyRouteTest",
                "dev.soprano.gpu.DiscreteComputeTest",
                "dev.soprano.ui.MapCameraMotionTest",
                "dev.soprano.path.RouteExclusionsTest",
                "dev.soprano.path.SafeReturnCheckpointsTest",
                "dev.soprano.path.RouteItineraryTest",
                "dev.soprano.game.transport.ElytraReservePolicyTest",
                "dev.soprano.game.transport.StriderRouteTest",
                "dev.soprano.game.transport.PearlTrajectoryTest",
                "dev.soprano.build.BuildLayersTest",
                "dev.soprano.build.ScaffoldLedgerTest",
                "dev.soprano.build.SchematicReaderTest",
                "dev.soprano.task.TaskHistoryTest",
                "dev.soprano.task.JobSpecTest",
                "dev.soprano.task.BrewPlanTest",
                "dev.soprano.task.GatherFishingTest",
                "dev.soprano.task.GatherCommerceTest",
                "dev.soprano.inventory.SignStorageTest",
                "dev.soprano.inventory.StorageKnowledgeTest",
                "dev.soprano.build.BuildPlanTest",
                "dev.soprano.game.transport.GlideSearchTest",
                "dev.soprano.game.transport.RailGeometryTest",
                "dev.soprano.game.MinecraftTerrainTest",
                "dev.soprano.game.SprintControlTest",
                "dev.soprano.game.LiveGpuRouteTest",
                "dev.soprano.game.VitalityPolicyTest",
                "dev.soprano.game.AdaptivePlanningTest",
                "dev.soprano.ui.MovementProfilesTest",
                "dev.soprano.game.ConstructionPlacementTest",
            ):
                arguments = ["-ea", "-cp", test_classpath, suite]
                if args.test_gpu and suite in ("dev.soprano.gpu.DiscreteComputeTest", "dev.soprano.map.GpuSurveyRouteTest"):
                    arguments.append("--require-gpu")
                write_arguments(test_arguments, arguments)
                subprocess.run([str(jdk_tool(args.jdk, "java")), "@" + str(test_arguments)], cwd=staging, check=True)
        with tempfile.TemporaryDirectory(prefix=".wayclef-package-", dir=destination.parent) as package_directory:
            packaged = Path(package_directory) / destination.name
            package(classes, packaged, resource_modules)
            packaged.replace(destination)
    print(f"Built {destination}")
    print(f"SHA-256 {hashlib.sha256(destination.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
