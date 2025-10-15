load("//tools/bzl:junit.bzl", "junit_tests")
load(
    "//tools/bzl:plugins.bzl",
    "CORE_PLUGINS",
    "CUSTOM_PLUGINS",
)

plugins_list = ["//plugins/%s" % n for n in CORE_PLUGINS + CUSTOM_PLUGINS]

main_test_dependencies = [ "//java/com/google/gerrit/entities",
                                 "//java/com/google/gerrit/server",
                                 "//java/com/google/gerrit/server/data",
                                 "//java/com/google/gerrit/server/util/time",
                                 "//java/com/google/gerrit/testing:gerrit-test-util",
                                 "//lib:gson",
                                 "//lib:guava",
                                 "//lib/truth",
                                 "//lib/reflections" ]

combined_deps = plugins_list + main_test_dependencies

junit_tests(
    name = "events_tests",
    srcs = glob(["*.java"]),
    deps = combined_deps
)
