load("//tools/bzl:junit.bzl", "junit_tests")

junit_tests(
    name = "httpd_tests",
    srcs = glob(["**/*.java"]),
    deps = [
        "//java/com/google/gerrit/extensions:api",
        "//java/com/google/gerrit/httpd",
        "//java/com/google/gerrit/server",
        "//javatests/com/google/gerrit/util/http/testutil",
        "//java/com/google/gerrit/testing:gerrit-test-util",
        "//lib:gson",
        "//lib:guava",
        "//lib:gwtorm",
        "//lib:jimfs",
        "//lib:junit",
        "//lib:servlet-api-3_1-without-neverlink",
        "//lib:soy",
        "//lib/easymock",
        "//lib/guice",
        "//lib/guice:guice-servlet",
        "//lib/jgit/org.eclipse.jgit:jgit",
        "//lib/jgit/org.eclipse.jgit.junit:junit",
        "//lib/truth",
    ],
)
