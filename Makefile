######################################################################
# Makefile for gerrit main war file, and then the installer package.
#
# All build work is done by bazelisk ( a wrapper around bazel version anonymity.
#
# To make the installer package run "make installer"
#
# To build verything run "make all"
#   N.B. this will run tests also.
#
# To build everything but without tests run "make all-skip-tests"
#
######################################################################

SHELL := /bin/bash


# Work out this make files directory and the current PWD seperately
#mkfile_path := $(abspath $(lastword $(MAKEFILE_LIST)))
mkfile_path := $(shell dirname $(realpath $(lastword $(MAKEFILE_LIST))))
current_dir := $PWD

# Gerrit repo root can be set to the mkfile path location
GERRIT_ROOT= $(mkfile_path)
GERRIT_BAZELCACHE_PATH := $(shell echo $(realpath $(shell echo ~/.gerritcodereview/bazel-cache)))
GERRIT_BAZEL_BASE_PATH := $(shell bazelisk info output_base 2> /dev/null)

# JENKINS_WORKSPACE is the location where the job puts work by default, and we need to have assets paths relative
# to the workspace in some occasions.
JENKINS_WORKSPACE ?= $(GERRIT_ROOT)

# Works on OSX.
VERSION := $(shell $(GERRIT_ROOT)/build-tools/get_version_number.sh $(GERRIT_ROOT))
GERRIT_BAZEL_OUT := $(GERRIT_ROOT)/bazel-bin

GERRITMS_INSTALLER_OUT := target/gerritms-installer.sh
RELEASE_WAR_PATH := $(GERRIT_BAZEL_OUT)/release.war
CONSOLE_API_NAME := console-api
CONSOLE_API_DIR := $(GERRIT_BAZEL_OUT)/gerrit-console-api
# Please note the _deploy jar is the standalone one with the manifest included for running with java -jar xxx.jar
# The console-api.jar without deploy is a version for use in debugging locally with src files included for class path inclusion,
# it can be run using "./console-api" wrapper script.
CONSOLE_API_STANDALONE_JAR_PATH := $(CONSOLE_API_DIR)/$(CONSOLE_API_NAME)_deploy.jar

GERRITMS_GROUPID := com.google.gerrit

CONSOLE_ARTIFACTID := gerrit-console-api
CONSOLE_PREFIX := $(GERRITMS_GROUPID).$(CONSOLE_ARTIFACTID)

GERRITMS_ARTIFACTID := gerritms
GERRITMS_INSTALLER_ARTIFACTID := gerritms-installer

PKG_NAME   := gerritms
RPM_PREFIX := com.google.gerrit
PREFIX := $(RPM_PREFIX)/$(PKG_NAME)

# By default do not skip unit tests.
SKIP_TESTS :=

# maybe use /var/lib/jenkins/tmp it can still be ok on our local machines, maybe make switchable to /tmp but its ok for now.
JENKINS_DIRECTORY := /var/lib/jenkins
JENKINS_TMP_TEST_LOCATION := $(JENKINS_DIRECTORY)/tmp
DEV_BOX_TMP_TEST_LOCATION := /tmp/builds/gerritms

BUILD_USER=$USER
git_username=Testme

dependency_management_filter = ".*"

renoNoteUsage := make add-reno-note jiraNumber=<jiraNumber> section=<section> info=<info>

all: display_version conditionally-run-setup-env check_dependencies fast-assembly installer decide-run-tests
.PHONY:all

all-skip-tests: display_version conditionally-run-setup-env fast-assembly installer skip-tests
.PHONY:all-skip-tests

display_bazel_info:
	@echo "Path is ${PATH}"
	@echo "About to show bazel working version information"
	bazelisk info
.PHONY: display_bazel_info

display_version: display_bazel_info
	@echo "About to use the following version information."
	@python tools/workspace_status.py
.PHONY:display_version

snapshot_check:
ifeq ($(release),true)
	@echo "Checking for snapshots in a release build"
	./tools/check_for_snapshots.sh
endif
.PHONY:snapshot_check

# Check if any dependencies are out of date with alm-common-bom and fail the build if there are any.
#
check_dependencies: conditionally-run-setup-env
ifeq ($(officialBuild),true)
ifeq ($(skipDependencyManagement),true)
	@echo "Skipping check_dependencies. officialBuild=$(officialBuild),skipDependencyManagement=$(skipDependencyManagement)"
else
	./tools/check_sha.py --check --update_version=$(alm_common_bom_version) --filter=$(dependency_management_filter)
endif
else
	@echo "Skipping check_dependencies. officialBuild=$(officialBuild),skipDependencyManagement=$(skipDependencyManagement)"
endif
.PHONY:check_dependencies

# Update any dependencies managed by alm-common-bom
#
update_dependencies: conditionally-run-setup-env
ifeq ($(officialBuild),true)
ifeq ($(skipDependencyManagement),true)
	@echo "Skipping update_dependencies. officialBuild=$(officialBuild),skipDependencyManagement=$(skipDependencyManagement)"
else
	./tools/check_sha.py --update_version=$(alm_common_bom_version) --filter=$(dependency_management_filter)
endif
else
	@echo "Skipping update_dependencies. officialBuild=$(officialBuild),skipDependencyManagement=$(skipDependencyManagement)"
endif
.PHONY:update_dependencies

# Do an assembly without doing unit tests, of all our builds
#
fast-assembly: fast-assembly-gerrit fast-assembly-console
	@echo "Finished building assemblies"
.PHONY:fast-assembly
#
# Build just gerritMS
#
fast-assembly-gerrit:
	@echo
	@echo "************ Compile Gerrit Starting **************"
	@echo "Building GerritMS"
	bazelisk build release
	@echo
	@echo "************ Compile Gerrit Finished **************"
.PHONY:fast-assembly-gerrit
#
# Build just the console-api
#
fast-assembly-console:
	@echo "************ Compile Console-API Starting **************"
	@echo "Building console-api and rest of API components"
#	you could call bazelisk build //gerrit-console-api:console-api, or console-api_deploy
# if you are just debugging locally but best to get the full scan of API dependencies build and put into the api.zip.
	bazelisk build api

# In the same way we build release.war and rename later to be gerrit.war we DO NOT overwrite the gerrit.war as this
# is a completely different thing in bazel-bin ( i.e. gerrit without plugins ).  In the same way console-api.jar is a
# version of the console-api for locally debugging which can be run from the console-api script.
	@echo "Ready to pick up your console-api from bazel-bin/gerrit-console-api/console-api_deploy.jar"
	@echo "************ Compile Console-API Finished **************"
.PHONY:fast-assembly-console

clean:
	$(if $(GERRIT_BAZELCACHE_PATH),,$(error GERRIT_BAZELCACHE_PATH is not set))

	@echo "************ Clean Phase Starting **************"
	bazelisk clean
	rm -rf $(GERRIT_BAZEL_OUT)
	rm -f $(GERRIT_ROOT)/env.properties

	@# we should think about only doing this with a force flag, but for now always wipe the cache - only way to be sure!!
	@echo cache located here: $(GERRIT_BAZELCACHE_PATH)

	@# Going to clear out anything that looks like our known assets for now...!
	@echo
	@echo "Deleting Gerrit-GitMS-Interface cached assets..."
	@ls $(GERRIT_BAZELCACHE_PATH)/downloaded-artifacts/*gitms* || echo "Can't find downloaded-artifacts/*gitms*, maybe assets already deleted?"
	@rm -rf $(GERRIT_BAZELCACHE_PATH)/downloaded-artifacts/*gitms*

	@echo "************ Clean Phase Finished **************"

.PHONY:clean

nuclear-clean: clean
	$(if $(GERRIT_BAZEL_BASE_PATH),,$(error GERRIT_BAZEL_BASE_PATH is not set))

	@echo "******** !! Nuclear Clean Starting !! **********"
	@echo Bazel output base path located here: $(GERRIT_BAZEL_BASE_PATH)

	@# Clear bazel base output directory containing linked bazel-(out|bin|gerrit):
	@echo "Deleting Bazel output base path..."
	@rm -rf $(GERRIT_BAZEL_BASE_PATH)

	@echo "Performing full expunge via bazelisk to include its other packages like yarn / npm"
	@git clean -fdx && bazelisk clean --expunge
	@echo "******** !! Nuclear Clean Finished !! **********"

.PHONY:nuclear-clean

update_bazel_deps:
	@echo "***** Updating Bazel Dependencies Starting *****"

	./tools/check_sha.py -vpe

	@echo "***** Updating Bazel Dependencies Finished *****"
.PHONY:update_bazel_deps

update_bazel_dep:
	$(if $(FILTER),,$(error Expects an argument of FILTER=<name-of-dependency>))
	@echo "** Updating Single Bazel Dependency Starting ***"

	./tools/check_sha.py -vpe -f '.*$(FILTER).*'

	@echo "** Updating Single Bazel Dependency Finished ***"
.PHONY:update_bazel_dep


list-assets:
	@echo "************ List Assets Starting **************"
	@echo  "Jenkins workspace is: $(JENKINS_WORKSPACE)"

	./build-tools/list_asset_locations.sh $(JENKINS_WORKSPACE) true

	#$(eval ASSETS_FOUND=$(./build-tools/list_asset_locations.sh $(JENKINS_WORKSPACE) "false"))
	#@echo "ASSETS_FOUND: $(ASSETS_FOUND)"

	@echo "************ List Assets Finished **************"
.PHONY:list-assets

conditionally-run-setup-env:
  # We wish to conditionally run the setup-env target which does some configuration of the WANdisco repository details
  # and any other prereq setup.
  # then we can run this by default.  But if set and =FALSE then we must skip this step.
  ifeq ($(officialBuild),true)
  conditionally-run-setup-env: setup-env
  else
	@echo
	@echo "************ Skipped any WANDISCO specific environment setup *********"
  endif

.PHONY:conditionally-run-setup-env

setup-env:
	@echo
	@echo "************ Setup WANDISCO environment - starting *********"
	@echo "Running environmental scripts from: $(GERRIT_ROOT)"

	$(GERRIT_ROOT)/build-tools/setup-environment.sh $(GERRIT_ROOT)

	@echo "Getting alm-common-bom version..."
	$(eval alm_common_bom_version=$(shell mvn help:evaluate -Dexpression=alm-common-bom-version -DforceStdout -q))
	$(if $(alm_common_bom_version),,$(error Unable to get alm-common-bom version from maven.))

	@echo
	@echo "************ Setup environment - finished *********"
.PHONY:setup-env

# This target can be used to check variables supplied before other targets are called. It is directly tied
# with the maven validate phase so the maven wrapper can be used like so: mvn validate -DNEW_PROJECT_VERSION=x.x.x.x
validate:
ifneq ($(filter-out false,$(NEW_PROJECT_VERSION)),)
  # NEW_PROJECT_VERSION is set to false by the maven wrapper. Here we are filtering out the false value and checking
  # that the value is not null. So if the version is not null or not false then we will update-version-information with it
  validate: update-version-information
else
    # if new_version isn't supplied, it's a normal run, if isOfficialBuild is true then we should be checking current
    # version matches the tag version from git describe
    ifeq ($(officialBuild),true)
        validate: check-version-info
    endif
endif
.PHONY:validate

check-version-info:
	@echo "Running version script"
	./tools/validate-current-version.py || (echo "tools/validate-current-version.py script failed $$?"; exit 1)
	@echo
.PHONY:check-version-info

# Default value for NEW_PROJECT_VERSION is false. The ifneq check appears here also as well as in validate. This will
# allow for the target to be called directly via make. export NEW_PROJECT_VERSION=x.x.x.x && make update-version-information
update-version-information:
ifneq ($(NEW_PROJECT_VERSION),false)
	@echo "************ Running tools/version.py *********"
	@echo
	@echo "Value of NEW_PROJECT_VERSION: $(NEW_PROJECT_VERSION)"
	@echo "***********************************************"
	@echo
	@echo "Current version is: $(VERSION)"
	@echo
	@echo "Updating all versions to: $(NEW_PROJECT_VERSION)"
	@echo
	@echo "Running version script"
	./tools/version.py $(NEW_PROJECT_VERSION) || (echo "tools/version.py script failed $$?"; exit 1)
	@echo
	@echo "************ Versions updated *********"
endif
.PHONY: update-version-information

check_build_assets:
	# check that our release.war and console-api.jar items have been built and are available
	$(eval RELEASE_WAR_PATH=$(RELEASE_WAR_PATH))
	$(eval CONSOLE_API_STANDALONE_JAR_PATH=$(CONSOLE_API_STANDALONE_JAR_PATH))
	# Writing out a new file, so create new one.

	@echo "RELEASE_WAR_PATH=$(RELEASE_WAR_PATH)" >> "$(GERRIT_ROOT)/env.properties"
	@echo "INSTALLER_PATH=target" >> $(GERRIT_ROOT)/env.properties
	@echo "CONSOLE_API_STANDALONE_JAR_PATH=$(CONSOLE_API_STANDALONE_JAR_PATH)" >> $(GERRIT_ROOT)/env.properties
	@echo "Env.properties is saved to: $(GERRIT_ROOT)/env.properties)"

	@[ -f $(RELEASE_WAR_PATH) ] && echo release.war exists || ( echo release.war not exists && exit 1;)
	@[ -f $(CONSOLE_API_STANDALONE_JAR_PATH) ] && echo console-api exeutable exists || ( echo console-api exeutable does not exist && exit 1;)
.PHONY:check_build_assets

installer: check_build_assets
	@echo
	@echo "************ Installer Phase Starting **************"

	@echo "Building Gerrit Installer..."
	$(GERRIT_ROOT)/gerrit-installer/create_installer.sh $(RELEASE_WAR_PATH) $(CONSOLE_API_STANDALONE_JAR_PATH)
	@echo
	@echo "************ Installer Phase Finished **************"
.PHONY:installer

skip-tests:
	@echo "Skipping integration tests."
.PHONY:skip-tests

decide-run-tests:
  # We wish to conditionally run this target if we have been given an override RUN_INTEGRATION_TESTS flag, if its not set
  # then we can run this by default.  But if set and =FALSE then we must skip this step.
  ifeq ($(RUN_INTEGRATION_TESTS),false)
  decide-run-tests: skip-tests
  else
  decide-run-tests: run-acceptance-tests
  endif
.PHONY:decide-run-tests

run-acceptance-tests:
	@echo
	@echo "************ Acceptance Tests Starting **************"
	@echo "About to run the Gerrit Acceptance Tests. These are the minimum required set of tests needed to run to verify Gerrits integrity."
	@echo "We specify GERRITMS_REPLICATION_DISABLED=true so that replication is disabled."
	@echo "Tests with the following labels in their BUILD files are disabled : [ elastic, docker, disabled, replication ]"

	bazelisk test --cache_test_results=NO --test_env=GERRITMS_REPLICATION_DISABLED=true \
	         --test_tag_filters=-elastic,-docker,-disabled,-replication  \
	         //...

	@echo
	@echo "************ Acceptance Tests Finished **************"
.PHONY:run-acceptance-tests

run-test-suite:
	$(if $(filter),,$(error 'filter' is not set))
	@echo
	@echo "******** Test Suite Starting:  $(filter) *********"

	bazelisk test --cache_test_results=NO --test_env=GERRITMS_REPLICATION_DISABLED=true --test_tag_filters=-elastic,-docker,-disabled,-replication  $(filter)
	@echo
	@echo "****** Test Suite Finished: $(filter) ************"
.PHONY:run-test-suite

run-single-test:
	$(if $(testFilter),,$(error testFilter is not set))
	$(if $(testLocation),,$(error testLocation is not set))
	$(if $(testOptionalArgs),,$(info testOptionalArgs are being used.))
	@echo
	@echo "************ Single Gerrit Acceptance Tests Starting **************"
	@echo "About to run the a Single Set of Gerrit Acceptance Tests."
	@echo "Running with specific testFilter = $(testFilter)"
	@echo "Running in test asset location TEST_ASSET_LOCATION = $(testLocation)"
	@echo "We specify GERRITMS_REPLICATION_DISABLED=true so that replication is disabled."
	@echo "Tests with the following labels in their BUILD files are disabled by default: [ elastic, docker, disabled, replication ]"
	@echo "N.B. For Debugging use  testOptionalArgs=--java_debug to make tests wait for debugger attach! "

	bazelisk test --cache_test_results=NO --test_env=GERRITMS_REPLICATION_DISABLED=true \
		--test_tag_filters=-elastic,-docker,-disabled,-replication,-delete-project \
		--test_output=streamed \
		--test_filter=$(testFilter) $(testLocation) $(testOptionalArgs)
	@echo
	@echo "************ Single Gerrit Acceptance Tests Finished **************"

.PHONY:run-single-test

detect-flaky-acceptance-tests:
	@echo
	@echo "************ Acceptance Flaky Test Detector Starting **************"
	@echo "About to run a slow version of the Gerrit acceptance tests to try and identify tests which are flaky (i.e. intermittently failing across multiple runs."
	@echo "We specify GERRITMS_REPLICATION_DISABLED=true so that replication is disabled."
	@echo "Tests with the following labels in their BUILD files are disabled : [ elastic, docker, disabled, replication ]"

	# Increase the value of runs_per_test if the failure happens rarely.
	bazelisk test --cache_test_results=NO --test_env=GERRITMS_REPLICATION_DISABLED=true --runs_per_test=10 --runs_per_test_detects_flakes=true --test_tag_filters=-elastic,-docker,-disabled,-replication  //...
	@echo
	@echo "************ Acceptance Flaky Test Detector Finished **************"
.PHONY:detect-flaky-acceptance-tests

# Wrapper around a script to simplify adding reno notes.
add-reno-note:
	$(if $(jiraNumber),,$(error jiraNumber is not set, usage is: $(renoNoteUsage)))
	$(if $(section),,$(error section is not set, usage is: $(renoNoteUsage)))
	$(if $(info),,$(error info is not set, usage is: $(renoNoteUsage)))
	tools/add-reno-note.sh -j "$(jiraNumber)" -s "$(section)" -i "$(info)"
.PHONY:add-reno-note

# Print out reno report as it would be during open source release.
show-reno-report:
	tools/show-reno-report.sh
.PHONY:show-reno-report

release-notes-check:
ifeq ($(release),true)
ifeq ($(skipReleaseNotesEnforcement), true)
	@echo "Skipping release-notes-check. release=$(release),skipReleaseNotesEnforcement=$(skipReleaseNotesEnforcement)"
else
	@echo "Checking that release notes are generated correctly."
	tools/show-reno-report.sh --error-on-empty-report
endif
endif
.PHONY:release-notes-check

help:
	@echo
	@echo Available popular targets:
	@echo
	@echo "   make all                                -> Will compile all packages, and create installer, and finish with integration tests"
	@echo "   make clean                              -> Will clean out our integration test, package build and tmp build locations."
	@echo "   make nuclear-clean                      -> Will perform a standard clean and additionally remove the bazel cache directory for this project."
	@echo "   make setup-env                          -> Runs util script to copy local.properties file into the correct location for builds to use our WANDISCO custom repo"
	@echo "   make update-version-information         -> Export variable NEW_PROJECT_VERSION=x.x.x.x and this target will update the version.bzl and all pom files and files to that version"
	@echo "   make check-version-info                 -> Compares the output of tools/workspace_status.py against the current version in version.bzl"
	@echo "   make validate                           -> Tied to the maven validate phase, this will check if there are any appropriate env variables set and call the relevant makefile target"
	@echo "   make clean fast-assembly                -> will compile and build GerritMS without the installer"
	@echo "   make fast-assembly                      -> will just build the GerritMS and ConsoleAPI packages"
	@echo "   make fast-assembly-gerrit               -> will just build the GerritMS package"
	@echo "   make fast-assembly-console              -> will just build the GerritMS Console API package"
	@echo "   make clean fast-assembly installer      -> will build the packages and installer asset"
	@echo "   make installer                          -> will build the installer asset using already built packages"
	@echo "   make run-acceptance-tests               -> will run the Gerrit acceptance tests, against the already built packages"
	@echo "   make detect-flaky-acceptance-tests      -> Will run the acceptance tests with more iterations and report on intermittent failures."
	@echo "   make run-single-test		              -> will run a Single Gerrit acceptance test class or group, against the already built packages"
	@echo "   		                                     e.g.  make run-single-test testFilter=com.google.gerrit.server.replication.SingletonEnforcementTest testLocation=//javatests/com/google/gerrit/server:server_tests"
	@echo "   make list-assets                        -> Will list all assets from a built project, and return them in env var: ASSETS_FOUND"
	@echo "   make add-reno-note                	  -> Will add a reno note, expects jiraNumber, section and info arguments to be supplied."
	@echo "   make show-reno-report                   -> Will show a reno report as it would be generated through open sourcing."
	@echo "   make release-notes-check                -> Checks that release notes are generated correctly."
	@echo "   make help                               -> Display available targets"
.PHONY:help
