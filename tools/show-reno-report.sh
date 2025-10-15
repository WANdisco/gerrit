#!/bin/bash --noprofile
myName=${0##*/}
errorOnEmptyReport=false

function logError() {
  echo "${myName}: ERROR: $*" 1>&2
}

function die() {
  logError "$*"
  exit 1
}

function usage() {
   echo
   echo "$myName"
   echo
   echo "This is a wrapper script for showing a reno report."
   echo
   echo "Syntax: $myName [-h|--help] -e|--error-on-empty-report"
   echo
   echo "Options:"
   echo "  -e|--error-on-empty-report If an empty reno report is generated and this flag is true, the script will exit with an error."
   echo "  -h|--help               Print help."
   echo
}

function parseArguments() {
  while [[ $# -gt 0 ]]; do
    case $1 in
      -e|--error-on-empty-report)
        errorOnEmptyReport=true
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        logError "Cannot process positional argument: $1, please see usage."
        usage
        exit 1
        ;;
    esac
  done
}

parseArguments "$@"

if [[ -z "$(which reno)" ]]; then
  die "reno is not installed. (Can be installed with 'pip install reno')"
fi

# If unable to generate the base report, log and exit.
if ! renoReport=$(reno report . 2>&1); then
  logError "reno report error:"
  echo -e "=========="
  echo -e "$renoReport"
  echo -e "=========="
  die "Encountered an error trying to generate the reno report"
fi

# Refine the report.
renoReport=$(echo -e "$renoReport" | sed '0,/^Release Notes$/d' | grep -v "^\.\." | grep -v ^"[ =]*"$ \
            | sed '/^New Features/i \\n' | sed '/^Known Issues/i \\n' | sed '/^Upgrade Notes/i \\n' \
            | sed '/^Deprecation Notes/i \\n' | sed '/^Critical Issues/i \\n' | sed '/^Security Issues/i \\n' \
            | sed '/^Bug Fixes/i \\n' | sed '/^Other Notes/i \\n' | cat -s)

# If the report is empty after being refined, log and exit.
if [[ -z "$renoReport" ]]; then
  logError "reno report was generated but it was empty."
  logError "make sure there are reno notes in releasenotes/notes and that they are committed to git"
  if [[ "$errorOnEmptyReport" == "true" ]]; then
    exit 1
  else
    exit 0
  fi
fi

echo -e "$renoReport"
