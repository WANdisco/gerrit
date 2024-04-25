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

import os
import re
import subprocess
import sys

VERSION_BZL = 'version.bzl'
FNULL = open(os.devnull, 'w')


def die(error_message):
  print("-" * 80)
  print("ERROR")
  print("-" * 80)
  print("{0}".format(error_message))
  print("-" * 80)
  sys.exit(1)


def find_version_in_file(filename, compiled_pattern):
  """
    Given a pre compiled regex pattern, tries to find the pattern in the file given.
  """
  version = None
  try:
    with open(filename, 'r') as file:
      content = file.read()
      match = compiled_pattern.search(content)

      if match:
        version = match.group(2)  # Get the captured version group
        print("version.bzl : Current Gerrit Version: {0}".format(version))
      else:
        raise IOError("Version not found in '{0}'.".format(filename))

    return version

  except FileNotFoundError:
    die("File '{0}' not found.".format(filename))
  except Exception as err:
    die("{0}".format(err))


def compare_gerrit_version_against_gerrit_label(current_bzl_version):
  """
    If we are running as OfficialBuild = true, We will run this script as part of Makefile validation to ensure that the
    current version found in the version.bzl file matches what the script tools/workspace_status.py returns. The Makefile
    validation can also be triggered by calling mvn validate.
  """

  if current_bzl_version == None:
    raise ValueError("No current version.bzl file version found!!")

  script_path = os.path.join(os.path.dirname(__file__), 'workspace_status.py')

  if os.path.exists(script_path):
    try:
      result = subprocess.Popen(['python', script_path], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
      out, error = result.communicate()

      script_output = out.decode('utf-8')
      line_pattern = 'STABLE_BUILD_GERRIT_LABEL.*'
      line_match = re.search(line_pattern, script_output)

      line = None
      if line_match:
        line = line_match.group(0)
      else:
        raise ValueError("Version pattern not found in the workspace_status.py script output.")

      if line is None:
        raise ValueError("A line with STABLE_BUILD_GERRIT_LABEL could not be found in the workspace_status.py output!!")

      try:
        git_describe_version = get_sanitized_git_describe_label(line)
      except Exception as err:
        die("{0}".format(err))

      if not git_describe_version == current_bzl_version:
        raise ValueError("Value returned by git describe label STABLE_BUILD_GERRIT_LABEL: %s \ndoes not match current "
                         "Gerrit version found in version.bzl %s" % (git_describe_version, current_bzl_version))

      else:
        print("-" * 80)
        print("SUCCESS")
        print("-" * 80)
        print("git describe label STABLE_BUILD_GERRIT_LABEL: %s \n"
              "matches current Gerrit version found in version.bzl %s"
              % (git_describe_version, current_bzl_version))
        print("-" * 80)


    except subprocess.CalledProcessError as err:
      die("running the script: {0}".format(err))
    except Exception as err:
      die("{0}".format(err))
  else:
    die("The script '{0}' was not found.".format(script_path))


def get_sanitized_git_describe_label(git_describe_label_version):
  """
     Looks at the version in the line extracted from the workspace_status.py script
     STABLE_BUILD_GERRIT_LABEL: v2.16.28-RP-1.11.0.2-SNAPSHOT-87-gb2df337f9a-dirty
     and uses a series of regex groupings to match and reconstruct the part we need to make
     the comparison which is this portion 2.16.28-RP-1.11.0.2-SNAPSHOT
  """

  """
     The regex to match the pattern is a series of regex groups denoted by parenthesis. The groups are broken
     down as follows:
     e.g.
     match group 2: gerrit_version: 2.16.28 identified by the group: (\d+\.\d+\.\d+)
     match group 3: rp_string: -RP- identified by the group : (-.\w-)
     match group 4: gitms_version: 1.11.0.2-SNAPSHOT with the -SNAPSHOT 
                    being a group also -> (\d+\.\d+\.\d+\.\d+(-\w+)?) which is (-\w+)?. The question mark denotes that
                    its optional as it may not be there.
     match group 5: this is the -SNAPSHOT match group -> (-\w+)?
     match group 6: This is the other information such as the dirty portion -> -87-gb2df337f9a-dirty as is identified
                    by the group: (-.\d+-\w+-\w+)?
     
  """
  version_pattern = r'\bv((\d+\.\d+\.\d+)(-.\w-)(\d+\.\d+\.\d+\.\d+(-\w+)?)(-.\d+-\w+-\w+)?)\b'

  found_version = None
  gerrit_version = None
  rp_string = None
  gitms_version = None
  match = re.search(version_pattern, git_describe_label_version)
  if match:

    if not len(match.groups()) >= 4:
      die("Version pattern not found. No groups matched. Check the version of STABLE_BUILD_GERRIT_LABEL.")

    if len(match.groups()) == 5:
      snapshot_identifier = match.group(5)
      if snapshot_identifier is not None and "-SNAPSHOT" in snapshot_identifier:
        print("STABLE_BUILD_GERRIT_LABEL contains SNAPSHOT in GitMS Version {0}".format(match.group(1)))

    if len(match.groups()) == 6:
      dirty_identifier = match.group(6)
      if dirty_identifier is not None and "dirty" in dirty_identifier:
        print("STABLE_BUILD_GERRIT_LABEL contains dirty label in the version {0}".format(match.group(1)))

    gerrit_version = match.group(2)
    rp_string = match.group(3)
    gitms_version = match.group(4)
    # This is the reconstructed version we care about we need to make the comparison.
    found_version = "{0}{1}{2}".format(gerrit_version, rp_string, gitms_version)

    print("current STABLE_BUILD_GERRIT_LABEL: {0}".format(found_version))
    return found_version.lstrip("v")
  else:
    die("Version pattern not found.")


def get_current_from_version_bzl():
  """
    Pre-compile the regex pattern and find it within the version.bzl file.
  """
  version_bzl_pattern = re.compile(r'^(GERRIT_VERSION = ")([-.\w]+)(")$', re.MULTILINE)
  return find_version_in_file(VERSION_BZL, version_bzl_pattern)


if __name__ == "__main__":
  """ Start the comparison """
  try:
    compare_gerrit_version_against_gerrit_label(get_current_from_version_bzl())
  except Exception as err:
    die("{0}".format(err))
