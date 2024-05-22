load("//tools/bzl:junit.bzl", "junit_tests")

junit_tests(
    name = "wandisco_pgm_tests",
    srcs = glob(["**/*.java"]),
    deps = [
        "//gerrit-console-api/src/main/java/com/google/gerrit/gerritconsoleapi/bindings:gerrit-console-api-min-dependency-overrides",
        "//java/com/google/gerrit/entities",
        "//java/com/google/gerrit/common:server",
        "//java/com/google/gerrit/pgm/wandisco",
        "//java/com/google/gerrit/server",
        "//java/com/google/gerrit/testing:gerrit-test-util",
        "//javatests/com/google/gerrit/server:server_tests",
        "//lib:jgit",
        "@guava-retrying//jar",
        "@mockito//jar",
        "@truth//jar",
    ],
)
