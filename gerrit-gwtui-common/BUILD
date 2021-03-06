load("@rules_java//java:defs.bzl", "java_library")
load("//tools/bzl:gwt.bzl", "gwt_module")
load("//tools/bzl:java.bzl", "java_library2")
load("//tools/bzl:junit.bzl", "junit_tests")

EXPORTED_DEPS = [
    "//java/com/google/gerrit/common:client",
    "//java/com/google/gwtexpui/clippy",
    "//java/com/google/gwtexpui/globalkey",
    "//java/com/google/gwtexpui/progress",
    "//java/com/google/gwtexpui/safehtml",
    "//java/com/google/gwtexpui/user:agent",
]

DEPS = ["//lib/gwt:user-neverlink"]

SRC = "src/main/java/com/google/gerrit/"

gwt_module(
    name = "client",
    srcs = glob(["src/main/**/*.java"]),
    exported_deps = EXPORTED_DEPS,
    gwt_xml = SRC + "GerritGwtUICommon.gwt.xml",
    resources = glob(
        ["src/main/**/*"],
        exclude = [SRC + "client/**/*.java"] + [
            SRC + "GerritGwtUICommon.gwt.xml",
        ],
    ),
    visibility = ["//visibility:public"],
    deps = DEPS,
)

java_library2(
    name = "client-lib",
    srcs = glob(["src/main/**/*.java"]),
    exported_deps = EXPORTED_DEPS,
    resources = glob(["src/main/**/*"]),
    visibility = ["//visibility:public"],
    deps = DEPS,
)

java_library(
    name = "diffy_logo",
    data = [
        "//lib:LICENSE-CC-BY3.0-unported",
        "//lib:LICENSE-diffy",
    ],
    resources = glob(["src/main/resources/com/google/gerrit/client/diffy*.png"]),
    visibility = ["//visibility:public"],
)

junit_tests(
    name = "client_tests",
    srcs = glob(["src/test/java/**/*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        ":client",
        "//lib:junit",
        "//lib/gwt:dev",
        "//lib/jgit/org.eclipse.jgit:jgit",
    ],
)
