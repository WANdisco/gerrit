#!/bin/bash

# Copyright (c) 2015, WANDisco
# All rights reserved.

# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions are met:
#
# 1. Redistributions of source code must retain the above copyright notice, this
# list of conditions and the following disclaimer.
#
# 2. Redistributions in binary form must reproduce the above copyright notice,
# this list of conditions and the following disclaimer in the documentation
# and/or other materials provided with the distribution.
#
# 3. Neither the name of the copyright holder nor the names of its contributors
# may be used to endorse or promote products derived from this software without
# specific prior written permission.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
# AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
# IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
# DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
# FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
# DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
# SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
# CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
# OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
# OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

function print_help {
  usage="Usage: $(basename "$0")

      -h  Show this help
      -c  Location of the gerrit.config file
      -u  Gerrit account username
      -p  Gerrit account password"
  echo "$usage"
}

function die {
  echo "ERROR: $@"
  exit 1;
}

function bold {
    local message="$1"

    echo -e "\033[1m$message\033[0m"
}

function validate_input {

  if [ -z "$USERNAME" ]; then
    die "Gerrit account username (-u) must be provided"
  fi

  if [ -z "$PASSWORD" ]; then
    die "Gerrit account password (-p) must be provided"
  fi

  if [ -z "$CONFIG" ]; then
    die "Gerrit config location (-c) must be provided"
  else
    # Check gerrit config provided exists and is readable
    if [ ! -f "$CONFIG" ]; then
      die "$CONFIG does not exist"
    else
      if [ ! -r "$CONFIG" ]; then
       die "$CONFIG is not readable"
      fi
    fi
  fi

  if [ -z "$CHANGEID" ]; then
    die "ChangeID (-i) to reindex must be provided"
  else
    # ChangeID must be numeric
    if [[ ! "$CHANGEID" =~ ^-?[0-9]+$ ]]; then
      die "ChangeID (-i) must be numeric"
    fi
  fi
}

function parse_gerrit_url {
  GIT_CONFIG="$CONFIG"
  export GIT_CONFIG
  GERRIT_URL=$(git config gerrit.canonicalWebUrl)
  unset GIT_CONFIG

  if [ -z "$GERRIT_URL" ]; then
    die "Cannot parse gerrit.canonicalWebUrl from $CONFIG"
  fi
}

while getopts u:p:c:i:h opt
do
  case "$opt" in
    u) USERNAME="$OPTARG";;
    p) PASSWORD="$OPTARG";;
    c) CONFIG="$OPTARG";;
    i) CHANGEID="$OPTARG";;
    h) print_help
       exit 1
       ;;
  esac
done

validate_input
parse_gerrit_url

# Make sure curl is installed
if ! type -p "curl" >/dev/null 2>&1; then
  die "cURL is required to reindex, but could not be found."
fi

bold "Reindexing change $CHANGEID"
URL="$GERRIT_URL"
URL+="a/changes/$CHANGEID/index"

curl --digest -X POST -u "$USERNAME":"$PASSWORD" "$URL"
