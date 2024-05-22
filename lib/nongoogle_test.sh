#!/bin/sh

# This test ensures that new dependencies in nongoogle.bzl go through LC review.

set -eux

bzl=$(pwd)/tools/nongoogle.bzl

TMP=$(mktemp -d || mktemp -d -t /tmp/tmp.XXXXXX)

grep 'name = "[^"]*"' ${bzl} | sed 's|^[^"]*"||g;s|".*$||g' | sort > $TMP/names

cat << EOF > $TMP/want
backward-codecs
cglib-3_2
commons-io
dropwizard-core
eddsa
equalsverifier
error-prone-annotations
flogger
flogger-log4j-backend
flogger-slf4j-backend
flogger-system-backend
gerrit-gitms-shared
guava
guava-testlib
guice-assistedinject
guice-library
guice-servlet
hamcrest
impl-log4j
j2objc
jackson-annotations
jackson-core
jackson-databind
javassist
javax-activation
jcl-over-slf4j
jimfs
jruby
log-api
log-ext
log4j
lucene-analyzers-common
lucene-core
lucene-misc
lucene-queryparser
mina-core
nekohtml
objenesis
openid-consumer
reflections
soy
sshd-mina
sshd-osgi
sshd-sftp
truth
truth-java8-extension
truth-liteproto-extension
truth-proto-extension
tukaani-xz
xerces
EOF

diff -u $TMP/names $TMP/want
