load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "metrics",
    srcs = glob(["**/*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        "//java/com/google/gerrit/common:server",
        "//java/com/google/gerrit/extensions:api",
        "//java/com/google/gerrit/lifecycle",
        "//lib:guava",
        "//lib/flogger:api",
        "//lib/guice",
        "//lib/jgit/org.eclipse.jgit:jgit",
    ],
)
