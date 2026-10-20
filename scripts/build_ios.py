#!/usr/bin/env python3
"""Build the shared framework and a small Swift host with the pinned official SDK.

Run on macOS through project-dev. All generated files stay in iosApp/build/.
Use --device to compile an unsigned physical-device app, --test UDID for UI tests.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
from prepare_ios_sdk import prepare

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "iosApp/build"

def uid(name):
    return hashlib.sha1(name.encode()).hexdigest()[:24].upper()

def value(obj):
    if isinstance(obj, dict):
        return "{\n" + "\n".join(f"{value(k)} = {value(v)};" for k, v in obj.items()) + "\n}"
    if isinstance(obj, list):
        return "(" + ", ".join(value(v) for v in obj) + ")"
    return json.dumps(str(obj))

def project():
    objects = {}
    def add(identifier, isa, **fields):
        key = uid(identifier); objects[key] = {"isa": isa, **fields}; return key
    sources = []
    notices = []
    refs = []
    for file in ["GemmaPocketApp.swift", "IOSServices.swift", "ModelTransfer.swift", "IOSPhotos.swift", "IOSPhotoIndex.swift"]:
        ref = add(file, "PBXFileReference", lastKnownFileType="sourcecode.swift", path="../../" + file, sourceTree="<group>")
        refs.append(ref); sources.append(add("build-" + file, "PBXBuildFile", fileRef=ref))
    for file in ["LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md"]:
        ref = add("notice-" + file, "PBXFileReference", lastKnownFileType="text", path="../../../" + file, sourceTree="<group>")
        refs.append(ref); notices.append(add("build-notice-" + file, "PBXBuildFile", fileRef=ref))
    testref = add("testref", "PBXFileReference", lastKnownFileType="sourcecode.swift", path="../../GemmaPocketUITests.swift", sourceTree="<group>")
    testfile = add("testfile", "PBXBuildFile", fileRef=testref)
    app = add("app", "PBXFileReference", explicitFileType="wrapper.application", path="GemmaPocket.app", sourceTree="BUILT_PRODUCTS_DIR")
    testapp = add("testapp", "PBXFileReference", explicitFileType="wrapper.cfbundle", path="GemmaPocketUITests.xctest", sourceTree="BUILT_PRODUCTS_DIR")
    products = add("products", "PBXGroup", children=[app, testapp], name="Products", sourceTree="<group>")
    group = add("group", "PBXGroup", children=refs + [testref, products], sourceTree="<group>")
    package = add("package", "XCLocalSwiftPackageReference", relativePath="../LiteRTLM-0.18.0")
    library = add("library", "XCSwiftPackageProductDependency", package=package, productName="LiteRTLM")
    link = add("link", "PBXBuildFile", productRef=library)
    sourcephase = add("sourcephase", "PBXSourcesBuildPhase", buildActionMask="2147483647", files=sources, runOnlyForDeploymentPostprocessing="0")
    frameworkphase = add("frameworkphase", "PBXFrameworksBuildPhase", buildActionMask="2147483647", files=[link], runOnlyForDeploymentPostprocessing="0")
    resourcephase = add("resourcephase", "PBXResourcesBuildPhase", buildActionMask="2147483647", files=notices, runOnlyForDeploymentPostprocessing="0")
    testphase = add("testphase", "PBXSourcesBuildPhase", buildActionMask="2147483647", files=[testfile], runOnlyForDeploymentPostprocessing="0")
    settings = {
        "SDKROOT": "iphoneos", "IPHONEOS_DEPLOYMENT_TARGET": "16.0", "SWIFT_VERSION": "5.0", "ARCHS": "arm64",
        "TARGETED_DEVICE_FAMILY": "1,2", "CLANG_ENABLE_MODULES": "YES",
        "SWIFT_OPTIMIZATION_LEVEL": "-Onone", "DEBUG_INFORMATION_FORMAT": "dwarf", "ENABLE_TESTABILITY": "YES",
        "GENERATE_INFOPLIST_FILE": "YES", "PRODUCT_NAME": "$(TARGET_NAME)", "ONLY_ACTIVE_ARCH": "YES",
        "SUPPORTED_PLATFORMS": "iphoneos iphonesimulator", "SUPPORTS_MACCATALYST": "NO",
        "SWIFT_STRICT_CONCURRENCY": "minimal", "ENABLE_USER_SCRIPT_SANDBOXING": "YES",
    }
    projectconfig = add("projectconfig", "XCBuildConfiguration", buildSettings=settings, name="Debug")
    projectlist = add("projectlist", "XCConfigurationList", buildConfigurations=[projectconfig], defaultConfigurationIsVisible="0", defaultConfigurationName="Debug")
    appsettings = {
        "PRODUCT_BUNDLE_IDENTIFIER": "com.spawnlau.gemmapocket.ios", "MARKETING_VERSION": "0.6.0", "CURRENT_PROJECT_VERSION": "8",
        "INFOPLIST_KEY_CFBundleDisplayName": "Gemma Gallery", "INFOPLIST_KEY_UILaunchScreen_Generation": "YES",
        "INFOPLIST_KEY_UIApplicationSceneManifest_Generation": "YES", "INFOPLIST_KEY_UISupportedInterfaceOrientations": "UIInterfaceOrientationPortrait",
        "INFOPLIST_FILE": "$(SRCROOT)/../../Info.plist",
        "FRAMEWORK_SEARCH_PATHS": ["$(inherited)", "$(SRCROOT)/../../../shared/build/bin/$(KMP_TARGET)/debugFramework"],
        "OTHER_LDFLAGS": ["$(inherited)", "-framework", "GemmaShared", "-lsqlite3", "-lc++"],
        "LD_RUNPATH_SEARCH_PATHS": ["$(inherited)", "@executable_path/Frameworks"],
    }
    appconfig = add("appconfig", "XCBuildConfiguration", buildSettings=appsettings, name="Debug")
    applist = add("applist", "XCConfigurationList", buildConfigurations=[appconfig], defaultConfigurationIsVisible="0", defaultConfigurationName="Debug")
    target = add("target", "PBXNativeTarget", buildConfigurationList=applist, buildPhases=[sourcephase, frameworkphase, resourcephase], buildRules=[], dependencies=[], name="GemmaPocket", packageProductDependencies=[library], productName="GemmaPocket", productReference=app, productType="com.apple.product-type.application")
    proxy = add("proxy", "PBXContainerItemProxy", containerPortal=uid("project"), proxyType="1", remoteGlobalIDString=target, remoteInfo="GemmaPocket")
    dependency = add("dependency", "PBXTargetDependency", target=target, targetProxy=proxy)
    testconfig = add("testconfig", "XCBuildConfiguration", buildSettings={"PRODUCT_BUNDLE_IDENTIFIER": "com.spawnlau.gemmapocket.ios.uitests", "TEST_TARGET_NAME": "GemmaPocket"}, name="Debug")
    testlist = add("testlist", "XCConfigurationList", buildConfigurations=[testconfig], defaultConfigurationIsVisible="0", defaultConfigurationName="Debug")
    tests = add("tests", "PBXNativeTarget", buildConfigurationList=testlist, buildPhases=[testphase], buildRules=[], dependencies=[dependency], name="GemmaPocketUITests", productName="GemmaPocketUITests", productReference=testapp, productType="com.apple.product-type.bundle.ui-testing")
    main = add("project", "PBXProject", attributes={"LastUpgradeCheck": "2710", "TargetAttributes": {target: {"CreatedOnToolsVersion": "27.1"}, tests: {"TestTargetID": target}}}, buildConfigurationList=projectlist, compatibilityVersion="Xcode 14.0", developmentRegion="en", hasScannedForEncodings="0", knownRegions=["en", "Base"], mainGroup=group, productRefGroup=products, projectDirPath="", projectRoot="", packageReferences=[package], targets=[target, tests])
    folder = BUILD / "project/GemmaPocket.xcodeproj"; folder.mkdir(parents=True, exist_ok=True)
    (folder / "project.pbxproj").write_text("// !$*UTF8*$!\n" + value({"archiveVersion": "1", "classes": {}, "objectVersion": "56", "objects": objects, "rootObject": main}))
    schemes = folder / "xcshareddata/xcschemes"; schemes.mkdir(parents=True, exist_ok=True)
    def ref(identifier, name, buildable):
        return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{identifier}" BuildableName="{buildable}" BlueprintName="{name}" ReferencedContainer="container:GemmaPocket.xcodeproj"/>'
    appref = ref(target, "GemmaPocket", "GemmaPocket.app"); testrefxml = ref(tests, "GemmaPocketUITests", "GemmaPocketUITests.xctest")
    (schemes / "GemmaPocket.xcscheme").write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2710" version="1.3">
<BuildAction parallelizeBuildables="NO" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="NO" buildForArchiving="YES" buildForAnalyzing="YES">{appref}</BuildActionEntry></BuildActionEntries></BuildAction>
<TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables><TestableReference skipped="NO">{testrefxml}</TestableReference></Testables></TestAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{appref}</BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Debug" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"/><AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Debug" revealArchiveInOrganizer="YES"/>
</Scheme>''')
    return folder

def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--device", action="store_true"); parser.add_argument("--test", metavar="UDID"); parser.add_argument("--only-test"); parser.add_argument("--skip-framework", action="store_true"); args = parser.parse_args()
    BUILD.mkdir(parents=True, exist_ok=True)
    start = time.monotonic(); target = "iosArm64" if args.device else "iosSimulatorArm64"
    task = ":shared:linkDebugFramework" + target[0].upper() + target[1:]
    if not args.skip_framework:
        subprocess.run(["bash", "./gradlew", "--no-daemon", "--max-workers=2", task], cwd=ROOT, check=True)
    prepare()
    xcode = project()
    destination = "id=" + args.test if args.test else "generic/platform=" + ("iOS" if args.device else "iOS Simulator")
    argv = ["xcodebuild", "-project", str(xcode), "-scheme", "GemmaPocket", "-configuration", "Debug", "-sdk", "iphoneos" if args.device else "iphonesimulator", "-destination", destination, "-derivedDataPath", str(BUILD / ("device" if args.device else "simulator")), "-clonedSourcePackagesDirPath", str(BUILD / "packages"), "-jobs", "2", "KMP_TARGET=" + target, "CODE_SIGNING_ALLOWED=NO"]
    if args.test:
        result = BUILD / ("ui-tests-" + str(int(time.time())) + ".xcresult")
        argv += ["-parallel-testing-enabled", "NO", "-collect-test-diagnostics", "never", "-resultBundlePath", str(result), "test"]
        if args.only_test: argv += ["-only-testing:" + args.only_test]
    else: argv += ["build"]
    subprocess.run(argv, cwd=ROOT, check=True)
    app = BUILD / ("device" if args.device else "simulator") / "Build/Products" / ("Debug-iphoneos" if args.device else "Debug-iphonesimulator") / "GemmaPocket.app"
    for name in ("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md"):
        if (app / name).read_bytes() != (ROOT / name).read_bytes():
            raise RuntimeError("Missing or altered distribution notice: " + name)
    summary = {"platform": target, "app": str(app), "bundleId": "com.spawnlau.gemmapocket.ios", "liteRtLm": "0.18.0", "seconds": round(time.monotonic()-start, 2), "testUdid": args.test, "unsigned": True, "distributionNoticesVerified": True}
    (BUILD / ("device-build-summary.json" if args.device else "build-summary.json")).write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary))

if __name__ == "__main__": main()
