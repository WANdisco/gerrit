load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "reflections",
    data = ["//lib:LICENSE-Apache2.0"],
    visibility = ["//visibility:public"],
    exports = ["@reflections//jar"],
    runtime_deps = [":javassist"],
)

java_library(
    name = "javassist",
    data = ["//lib:LICENSE-Apache2.0"],
    exports = ["@javassist//jar"],
)
