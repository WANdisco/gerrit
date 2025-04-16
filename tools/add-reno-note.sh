#!/bin/bash --noprofile
myName=${0##*/}
numErrors=0
allowedSections="features issues upgrade deprecations critical security fixes other"

function logError() {
  echo "${myName}: ERROR: $*" 1>&2
  numErrors=$(( numErrors + 1))
}

function die() {
  logError "$*"
  exit 1
}

function logInfo() {
   echo "${myName}: INFO: $*"
}

function logWarn() {
   echo "${myName}: WARN: $*"
}

function usage() {
   echo
   echo "$myName"
   echo
   echo "This is a wrapper script for adding reno notes."
   echo
   echo "Syntax: $myName [-h|--help] -j|--jira-number <jiraNumber> -s|--section <section> -i|--info <info>"
   echo
   echo "Options:"
   echo "  -j|--jira-number The JIRA number this work is being done for."
   echo "  -s|--section     The section that 'info' should be added to. (features/issues/fixes)"
   echo "  -i|--info        The info to be added about the work completed."
   echo "  -h|--help        Print help."
   echo
}

function parseArguments() {
  jiraNumber=
  section=
  info=
  while [[ $# -gt 0 ]]; do
    case $1 in
      -j|--jira-number)
        jiraNumber="$2"
        shift
        shift
        ;;
      -s|--section)
        section="$2"
        shift
        shift
        ;;
      -i|--info)
        info="$2"
        shift
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        logError "Ignoring positional argument: $1"
        shift
        ;;
    esac
  done

  if [[ -z "$jiraNumber" ]]; then
    logError "-j|--jira-number is not set"
  fi

  if [[ -z "$section" ]]; then
    logError "-s|--section is not set"
  elif ! echo "$allowedSections" | tr " " "\n" | grep -Fqx "$section"; then
    logError "-s|--section=$section is not in the allowed sections=$allowedSections)"
  fi

  if [[ -z "$info" ]]; then
    logError "-i|--info is not set"
  fi

  if (( numErrors > 0 )); then
    usage
    die "One or more arguments have not been set."
  fi
}

if [[ -z "$(which reno)" ]]; then
  die "reno is not installed. (Can be installed with 'pip install reno')"
fi

parseArguments "$@"

logInfo "Adding reno note"
logInfo "jiraNumber = $jiraNumber"
logInfo "section = $section"
logInfo "info = $info"

reno_output=$(reno new "$jiraNumber" 2>&1)

if [[ "$?" != "0" ]]; then
  die "error creating reno note: $reno_output"
fi

filename=$(echo "$reno_output" | grep "Created new notes file in" | sed 's/.* //g')

if [[ -z "$filename" ]]; then
  die "Couldn't get the name of the reno note file."
elif [[ ! -f "$filename" ]]; then
  die "reno note file=$filename is either not a file or does not exist."
fi

cat << EOF > "$filename"
---
${section}:
  - $info
EOF

logInfo "file = $filename"
logInfo "reno note added successfully!"
logInfo "Note: the release note will need to be committed to git for it to show in the report."
echo "=========="
cat "$filename"
echo "=========="