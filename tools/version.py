#!/usr/bin/env python
# Copyright (C) 2014 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from __future__ import print_function
import argparse
import os.path
import re
import sys
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('version')
args = parser.parse_args()

VERSION_BZL = 'version.bzl'

newVersionInfo = args.version

DEST_PATTERN = r'\g<1>%s\g<3>' % newVersionInfo
FNULL = open(os.devnull, 'w')


def replace_in_file(filename, pattern):
  try:
    f = open(filename, "r")
    s = f.read()
    f.close()
    s = re.sub(pattern, DEST_PATTERN, s)
    f = open(filename, "w")
    f.write(s)
    f.close()
  except IOError as err:
    print('error updating %s: %s' % (filename, err), file=sys.stderr)
    sys.exit(1)


src_pattern = re.compile(r'^(\s*<version>)([-.\w]+)(</version>\s*)$', re.MULTILINE)


def find_pom_files(directory, fileType='pom.xml'):
  """ Recursive Find all of files ending in pom.xml within the current working directory which is gerrit """

  matching_pom_files = []
  for root, dirs, files in os.walk(directory):
    for filename in files:
      if filename.endswith(fileType):
        matching_pom_files.append(os.path.join(root, filename))
  return matching_pom_files


def run_maven_version_set_cmd(newVersion, path=None):
  """ Specifically run the mvn versions:set command. If a path is supplied then we use the -f flag """

  if path is not None:
    mvn_command = ["mvn", "versions:set", "-DnewVersion={0}".format(newVersion), "-f", "{0}".format(path)]
  else:
    mvn_command = ["mvn", "versions:set", "-DnewVersion={0}".format(newVersion)]

  run_mvn_cmd(mvn_command)


def run_mvn_cmd(mvn_command):
  """ Can be used to run any maven command """
  try:
    subprocess.check_call(mvn_command, stdout=FNULL, stderr=FNULL)
    print("Maven command [ {0} ] executed successfully.".format(' '.join(mvn_command)))
  except subprocess.CalledProcessError as e:
    print("Error executing Maven command:", e)
    sys.exit(1)


def update_all_gerrit_poms():
  """
   This will find all poms located in the gerrit directory and within any subfolders
   of gerrit. We then filter out any of the gerrit aggregator pom and its modules as
   it will be covered by its own maven command.
  """
  pom_files_paths = find_pom_files(os.getcwd())

  # Filtering the gerrit root pom and modules out of the seearch paths.
  # We don't need to call mvn versions:set -f path on these as the aggregator pom will supply
  # the version to its modules.
  filtered_paths = [path for path in pom_files_paths if
                    "gerrit/gerrit-installer-trigger" not in path and
                    "gerrit/gerrit-api-comparison" not in path and
                    "gerrit/gerrit-installer-build" not in path and
                    "gerrit/pom.xml" not in path]

  # Run the maben versions:set on the aggregator pom and its modules.
  run_maven_version_set_cmd(newVersionInfo)

  # Find the remaining poms that have not had mvn version:set run on them and run it on them.
  for pom_file_path in filtered_paths:

    if not os.path.exists(pom_file_path):
      raise IOError("File not found: %s" % pom_file_path)

    run_maven_version_set_cmd(newVersionInfo, pom_file_path)


def update_version_bzl():
  """ Update the version.bzl file """
  version_bzl_pattern = re.compile(r'^(GERRIT_VERSION = ")([-.\w]+)(")$', re.MULTILINE)
  replace_in_file(VERSION_BZL, version_bzl_pattern)


if __name__ == "__main__":
  update_version_bzl()
  update_all_gerrit_poms()
