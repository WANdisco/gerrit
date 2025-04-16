#/********************************************************************************
# * Copyright (c) 2014-2018 WANdisco
# *
# * Licensed under the Apache License, Version 2.0 (the "License");
# * you may not use this file except in compliance with the License.
# * You may obtain a copy of the License at
# * http://www.apache.org/licenses/LICENSE-2.0
# *
# * Apache License, Version 2.0
# *
# ********************************************************************************/
load("@rules_java//java:defs.bzl", "java_library")

SRCS = "src/main/java/**"

RSRCS = "src/main/resources/"

BASE_DEPS = [
    "//java/com/google/gerrit/common:server",
    "//java/com/google/gerrit/extensions:api",
    "//java/com/google/gerrit/entities",
    "//java/com/google/gerrit/exceptions",
    "//java/com/google/gerrit/server",
    "//java/com/google/gerrit/sshd:sshd",
    "//java/com/google/gerrit/metrics",
    "//java/com/google/gerrit/server/schema",
    "//lib:guava",
    "//lib/guice:guice",
    "//lib/guice:guice-assistedinject",
    "//lib/guice:guice-servlet",
    "//lib:jgit",
    "//lib/log:api",
    "//lib/log:log4j",
]

# TREV TODO //gerrit-reviewdb, does this need abstracted to work with NoteDB?
DEPS = BASE_DEPS + [
    "//gerrit-console-api/src/main/java/com/google/gerrit/gerritconsoleapi/bindings:gerrit-console-api-min-dependency-overrides",
    "//lib/wandisco:gerrit-gitms-shared",
    "//lib:args4j",
    "//lib/auto:auto-value",
    "//lib/log:impl-log4j",
]

# Create a lib full of the source required to run a command line application which
# obtains information from gerrit state OR configuration.
java_library(
    name = "gerrit-console-api-module",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob([RSRCS + "*"]),
    visibility = ["//visibility:public"],
    deps = DEPS,
)

java_binary(
    name = "console-api",
    main_class = "com.google.gerrit.gerritconsoleapi.CLI_Launcher",
    visibility = ["//visibility:public"],
    runtime_deps = [
        ":gerrit-console-api-module",
    ],
)
