load("//tools/bzl:junit.bzl", "junit_tests")

junit_tests(
    name = "maillib_tests",
    size = "small",
    srcs = glob(
        ["**/*.java"],
    ),
    visibility = ["//visibility:public"],
    deps = [
        "//java/com/google/gerrit/mail",
        "//java/com/google/gerrit/reviewdb:server",
        "//java/com/google/gerrit/testing:gerrit-test-util",
        "//lib:gson",
        "//lib:guava-retrying",
        "//lib:gwtorm",
        "//lib/commons:codec",
        "//lib/guice",
        "//lib/jgit/org.eclipse.jgit:jgit",
        "//lib/jgit/org.eclipse.jgit.junit:junit",
        "//lib/truth",
        "//lib/truth:truth-java8-extension",
    ],
)
