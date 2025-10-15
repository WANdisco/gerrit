load("//tools/bzl:junit.bzl", "junit_tests")
load("//javatests/com/google/gerrit/acceptance:tests.bzl", "acceptance_tests")
load(
    "//tools/bzl:plugins.bzl",
    "CORE_PLUGINS",
    "CUSTOM_PLUGINS",
)

plugins_list = ["//plugins/%s" % n for n in CORE_PLUGINS + CUSTOM_PLUGINS]

## TODO Gerrit removed some of these test deps (http/testutil, @servlet-api//jar) so
##      we might be able to trim this list down later.
main_test_dependencies = ["//java/com/google/gerrit/entities",
                     "//java/com/google/gerrit/server",
                     "//java/com/google/gerrit/httpd",
                     "//java/com/google/gerrit/sshd",
                     "//java/com/google/gerrit/testing:gerrit-test-util",
                     "//javatests/com/google/gerrit/util/http/testutil",
                     "//lib:junit",
                     "//lib/truth",
                     "//lib/truth:truth-java8-extension",
                     "@jgit//org.eclipse.jgit.http.server:jgit-servlet",
                     "@jgit//org.eclipse.jgit:jgit",
                     "//lib/reflections",
                     "//lib/guice:guice",
                     "@servlet-api//jar",]

combined_deps = plugins_list + main_test_dependencies


junit_tests(
    name = "tests",
    resource_strip_prefix = "resources",
    resources = ["//resources/com/google/gerrit/cachetest:cache_test",  "//resources/com/google/gerrit/httpd/raw"],
    srcs = glob(["*Test.java"]),
    deps = combined_deps
)

acceptance_tests(
    srcs = glob(["*IT.java"]),
    group = "server_cache",
    labels = ["server"],
)
