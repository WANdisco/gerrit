load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "gerrit-gitms-shared",
    data = ["//lib:LICENSE-WANdisco"],
    visibility = ["//visibility:public"],
    exports = ["@gerrit-gitms-shared//jar"],
    runtime_deps = [
        "//lib/jackson:jackson-databind",
        "//lib/jackson:jackson-annotations",
    ],
)
