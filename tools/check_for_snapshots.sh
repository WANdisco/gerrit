#!/bin/bash --noprofile

snapshotStr="\-SNAPSHOT"
foundAnySnapshots=0

# Check version information.
echo "Checking version information for -SNAPSHOT"

versionBzl="./version.bzl"
if [[ ! -f "$versionBzl" ]]; then
  echo "Could not find: $versionBzl" 1>&2
  exit 2
fi

snapshotVersion=$(grep -v ^"#" version.bzl | grep -i "$snapshotStr")

if [[ -n "$snapshotVersion" ]]; then
  echo "Version information from $versionBzl contains -SNAPSHOT" 1>&2
  echo "$snapshotVersion" 1>&2
  foundAnySnapshots=1
else
  echo "Version information does not contain -SNAPSHOT"
fi

# Check RELEASE tags
echo "Checking RELEASE tags for any -SNAPSHOT versions"

releaseTagSnapshots=$(make display_version | grep -i "$snapshotStr")

if [[ -n "$releaseTagSnapshots" ]]; then
  echo "RELEASE tags contain \"-SNAPSHOT\"" 1>&2
  echo "$releaseTagSnapshots" 1>&2
  foundAnySnapshots=1
else
  echo "RELEASE tags do not contain any -SNAPSHOT versions."
fi

# Check WANdisco assets
echo "Checking WANdisco assets for any -SNAPSHOT versions"
checkSha="./tools/check_sha.py"
if [[ ! -x "$checkSha" ]]; then
  echo "Could not find or it is not executable: $checkSha" 1>&2
  exit 2
fi

snapshotAssets=$($checkSha --snapshot)

if [[ -n "$snapshotAssets" ]]; then
  echo "Snapshot assets found:" 1>&2
  echo "$snapshotAssets" 1>&2
  foundAnySnapshots=1
else
  echo "WANdisco assets do not contain any -SNAPSHOT versions."
fi

# Exit if any snapshots were found.
if [[ $foundAnySnapshots == 1 ]]; then
  echo "Snapshots were found in version info, RELEASE tags or WANdisco assets." 1>&2
  exit 1
else
  echo "No snapshots were found in version info, RELEASE tags or WANdisco assets."
fi
