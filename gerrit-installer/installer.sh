#!/bin/bash --noprofile


# Function that can control whether to print a message based on
# the value of an override. If override set to true that means that
# we always want to print the message regardless of the value of NON_INTERACTIVE
# We can also decide to print the mesage in bold.
# Example usages:
# info "This is a message" : Prints if NON_INTERACTIVE is not 1.
# info true "This is a message" : Always prints the message.
# info bold "This is a bold message" : Prints in bold if NON_INTERACTIVE is not 1.
# info true bold "This is a bold message" : Always prints in bold, regardless of NON_INTERACTIVE.
info() {
    local override=false
    local bold=false
    local message

    if [[ $# -eq 3 && $1 == "true" && $2 == "bold" ]]; then
        override=true
        bold=true
        message=$3
    elif [[ $# -eq 2 && $1 == "true" ]]; then
        override=true
        message=$2
    elif [[ $# -eq 2 && $1 == "bold" ]]; then
        bold=true
        message=$2
    else
        message=$1
    fi

    if [[ $override == "true" || "$NON_INTERACTIVE" != "1" ]]; then
        if [[ $bold == "true" ]]; then
            echo -e "\033[1m$message\033[0m"
        else
            echo -e "$message"
        fi
    fi
}

function perr() {
  echo -e "ERROR: $1" 1>&2
}

# Wrapper to run the command and return the exit code.
function runCommandGetExitCode() {
  runCommand "false" "$@"
  return $?
}

# Wrapper to run the command and exit if it fails.
function runCommandExitIfFail() {
  runCommand "true" "$@"
}

# Run a command and exit if it fails.
# This should only be called by either runCommandGetExitCode or runCommandExitIfFail
function runCommand() {
  # The first argument, if "true" will exit the script if the command returns an exit code != 0
  local exitIfFail="$1"
  shift

  typeset -i returnCode=0
  # Create a temporary file and check for success
  tmpFile=$(mktemp --tmpdir="${SCRATCH:-/tmp}") || {
    perr "Error: Failed to create a temporary file." >&2
    exit 1
  }

  eval "$*" 2>&1 | tee "$tmpFile"
  returnCode=${PIPESTATUS[0]}

  if [[ $returnCode -ne 0 ]]; then
    perr "Unexpected error encountered running command: $*"
    perr "Encountered on line $(caller 1) with exitCode=$returnCode"
    perr "Output:"
    while read -r aline; do
      perr "$aline"
    done < "$tmpFile"

    if [[ "$exitIfFail" == "true" ]]; then
      exit 1
    fi
  fi
  rm -f "$tmpFile"
  return $returnCode
}

# Checks if version is in the allowed_versions list.
function checkIfPreviousVersionIsAllowed() {
  local version="$1"
  shift
  local allowed_versions=("$@")

  for allowedVersion in "${allowed_versions[@]}"; do
    # If allowedVersion does not end with a . append one.
    # This is to prevent 1.1.0.0 matching 1.11.0.0, etc...
    if [[ "${allowedVersion:0-1}" != '.' ]]; then
      allowedVersion="$allowedVersion."
    fi

    if [[ "$version" == "$allowedVersion"* ]]; then
      return 0
    fi
  done

  return 1
}

# Checks the replicated version against the allowed replicated versions.
function isPreviousReplicatedVersionAllowed() {
  local version="$1"

  # If it contains -RP- it's a replicated version.
  if [[ "$version" != *"-RP-"* ]]; then
    return 1
  fi

  if ! checkIfPreviousVersionIsAllowed "${version//*-RP-/}" "${PREVIOUS_ALLOWED_RP_GERRIT_VERSIONS[@]}"; then
    return 1
  fi
  #Compare multisite version to multisite version e.g 1.12.1.1 to 1.12.0.1
  versionLessThan "${WD_GERRIT_VERSION//*-RP-/}" "${version//*-RP-/}"

  return $?
}

# Checks the vanilla version against the allowed vanilla versions.
function isPreviousVanillaVersionAllowed() {
  local version="$1"

  # If it contains -RP- it's not a vanilla version.
  [[ "$version" == *"-RP-"* ]] && return 1

  # If no previous allowed version listed, then return 1
  (( ${#PREVIOUS_ALLOWED_VANILLA_GERRIT_VERSIONS[@]} < 1 )) && return 1

  if ! checkIfPreviousVersionIsAllowed "$version" "${PREVIOUS_ALLOWED_VANILLA_GERRIT_VERSIONS[@]}"; then
    return 1
  fi

  versionLessThan "${CURRENT_GERRIT_VERSION//-RP-*/}" "$version"

  return $?
}

# Check if the version passed in is
function isPreviousVersionAllowed() {
  local version="$1"

  # If the version contains -RP-, then check if the
  # version is in the allowed replicated list, else
  # check it is in the allowed vanilla version list.
  if [[ "$version" == *"-RP-"* ]]; then
    isPreviousReplicatedVersionAllowed "$version"
  else
    isPreviousVanillaVersionAllowed "$version"
  fi

  return $?
}

function header() {
  if [[ ! "$NON_INTERACTIVE" == "1" ]]; then
    cat "resources/logo.txt"
    info "\n\n"
  fi
}

function next_screen() {
  if [[ ! "$NON_INTERACTIVE" == "1" ]]; then
    read -s -p "$1"
  fi
}

function urlencode() {
  local raw=$1
  local encoded=""
  local pos o

  for ((pos=0; pos < ${#raw}; pos++)); do
    c=${raw:$pos:1}
    case $c in
      [0-9a-zA-Z-_.~]) o=$c ;;
      *) printf -v o '%%%02x' "'$c" ;;
    esac
    encoded+="$o"
  done

  echo $encoded
}

## Removes double and trailing //'s from path
function sanitize_path() {
  local path=$(echo "$1" | tr -s / | sed 's:/*$::')
  echo "$path"
}

#Attempts to find the gerrit base path using the specified GERRIT_ROOT
function get_gerrit_base_path() {
    local gerrit_base_path=$(GIT_CONFIG="${1}/etc/gerrit.config" git config gerrit.basePath)
    echo "$gerrit_base_path"
}

function prereqs() {
  header

  info bold " GerritMS Version: $WD_GERRIT_VERSION Installation"
  info ""
  info bold " Install Documentation: "
  info ""
  info " $GERRITMS_INSTALL_DOC"
  info ""
  info " Welcome to the GerritMS installation. Before the install can continue,"
  info " you must:"
  info ""
  info " * Have one of the following gerrit versions installed before beginning:"
  info "     - Gerrit: $CURRENT_GERRIT_VERSION"
  for version in "${PREVIOUS_ALLOWED_RP_GERRIT_VERSIONS[@]}"; do
    info "     - Gerrit MS: $version"
  done
  info " * Have backed up your existing Gerrit database"
  info " * Have a version of GitMS (1.14.0.1 or higher) installed and running"
  info " * Have a replication group created in GitMS containing all Gerrit nodes"
  info " * Have a valid GitMS admin username/password"
  info " * Have an installation of JDK ${TARGET_JDK_VERSION} available"
  info " * Stop the Gerrit service on this node"
  info ""

  if [[ ! "$NON_INTERACTIVE" == "1" ]]; then
    REQUIREMENTS_MET=$(get_boolean "Do you want to continue with the installation?" "true")
    info
    if [[ "$REQUIREMENTS_MET" == "false" ]]; then
      info "Installation aborted by user"
      exit 0
    fi

    FIRST_NODE=$(get_boolean "Is this the first node GerritMS will be installed to?" "true")
  fi
}

function check_environment_variables_that_affect_curl() {
    if [[ -n "$HTTP_PROXY" ]] || [[ -n "$HTTPS_PROXY" ]] || [[ -n "$FTP_PROXY" ]] || [[ -n "$ALL_PROXY" ]] || [[ -n "$NO_PROXY" ]]; then
      info ""
      info " The following environment variables are set and will affect the use of 'curl': "
      info ""
      [[ -n "$HTTP_PROXY" ]] && echo " * HTTP_PROXY=$HTTP_PROXY"
      [[ -n "$HTTPS_PROXY" ]] && echo " * HTTPS_PROXY=$HTTPS_PROXY"
      [[ -n "$FTP_PROXY" ]] && echo " * FTP_PROXY=$FTP_PROXY"
      [[ -n "$ALL_PROXY" ]] && echo " * ALL_PROXY=$ALL_PROXY"
      [[ -n "$NO_PROXY" ]] && echo " * NO_PROXY=$NO_PROXY"
      info ""

      if [[ -z "$CURL_ENVVARS_APPROVED" ]]; then
        CURL_ENVVARS_APPROVED=$(get_boolean "Do you want to continue with the installation?" "true")
      fi
      if [[ "$CURL_ENVVARS_APPROVED" == "false" ]]; then
        info "Installation aborted by user"
        exit 0
      fi

    info ""
    fi
}

function check_user() {
  header
  local user=$(id -u -n)

  info " Currently installing as user: \033[1m$user\033[0m"
  info ""

  if [[ "$EUID" -eq 0 ]]; then
    info " WARNING: It is strongly advised that the GitMS and Gerrit services"
    info " are not run as root."
    info ""
  fi

  info " The current user should be the same as the owner of the GitMS service."
  info " If this is not currently the case, please exit and re-run the installer"
  info " as the appropriate user."
  info ""
  next_screen " Press [Enter] to Continue"
}

function check_executables() {
  local bins=0
  for bin in cp rm mv cut rsync mktemp curl grep tar gzip unzip xmllint git md5sum; do
    if ! type -p "$bin" >/dev/null 2>&1; then
      if [[ "$bins" -eq 0 ]]; then
        header
      fi
      info  " Could not find \033[1m$bin\033[0m command, please install it"
      info ""
      ((bins++))
    fi
  done
  mktemp --tmpdir="$TMPDIR" >/dev/null 2>&1;
  if [[ $? -ne 0 ]]; then
    if [[ "$bins" -eq 0 ]]; then
      header
    fi
    info " \033[1mmktemp\033[0m version does not support --tmpdir switch, please install the correct version"
    info ""
    ((bins++))
  fi
  if [[ "$bins" -ne 0 ]]; then
    exit 1
  fi

  return 0
}

function create_scratch_dir() {
  SCRATCH=$(mktemp -d)
}

## Check in ~/.gitconfig for the gitmsconfig property
## gitms_root can be derived from this
function find_gitms() {

  local gitms_config=$(GIT_CONFIG="$HOME/.gitconfig" git config core.gitmsconfig)

  if [[ -z "$gitms_config" || ! -e "$gitms_config" ]]; then
    ## check if the default gitms install folder is present
    if [[ -d "/opt/wandisco/git-multisite" ]]; then
      GITMS_ROOT="/opt/wandisco/git-multisite"
    else
      return
    fi
  else
    GITMS_ROOT=${gitms_config/"/replicator/properties/application.properties"/""}
  fi

  GITMS_ROOT_PROMPT=" [$GITMS_ROOT]"
}

function fetch_property() {
  cat "$APPLICATION_PROPERTIES" | grep "^$1 *=" | cut -f2- -d'='
}

function fetch_property_from_main_conf() {
  cat "$MAIN_CONF_FILE" | grep "^$1 *=" | cut -f2- -d'='
}

## Set a property in TMP_APPLICATION_PROPERTIES - also make sure any duplicate
## properties with the same key are not present by removing them
function set_property() {
  local tmp_file="$TMP_APPLICATION_PROPERTIES.tmp"
  ## First delete the property - this should not be necessary, but doesn't hurt
  ## The property must have the .'s escaped when we delete them, or they'll match
  ## against any character
  local escaped_property=$(echo "$1" | sed 's/\./\\./g')
  sed -e "/^$escaped_property=/ d" "$TMP_APPLICATION_PROPERTIES" > "$tmp_file" && mv "$tmp_file" "$TMP_APPLICATION_PROPERTIES"

  echo "$1=$2" >> "$TMP_APPLICATION_PROPERTIES"
}

## Remove a property in TMP_APPLICATION_PROPERTIES
function remove_property() {
  local tmp_file="$TMP_APPLICATION_PROPERTIES.tmp"
  for var in "$@"; do
    ## The property must have the .'s escaped when we delete them, or they'll match
    ## against any character
    local escaped_property=$(echo "$var" | sed 's/\./\\./g')
    ## Remove the property
    sed -e "/^$escaped_property=/ d" "$TMP_APPLICATION_PROPERTIES" > "$tmp_file" && mv "$tmp_file" "$TMP_APPLICATION_PROPERTIES"
  done
}

# During upgrade, remove any unused legacy properties
function remove_legacy_config_from_application_properties() {
  remove_property "gerrit.replicated.events.enabled.receive.distinct" \
                  "gerrit.replicated.events.enabled.local.republish.distinct" \
                  "gerrit.replicated.events.distinct.prefix" \
                  "gerrit.replicated.cache.enabled"
}

## With the GitMS root location, we can look up a lot of information
## and avoid asking the user questions
function fetch_config_from_application_properties() {
  SSL_ENABLED=$(fetch_property "ssl.enabled")
  GERRIT_ENABLED=$(fetch_property "gerrit.enabled")
  GERRIT_USERNAME=$(fetch_property "gerrit.username")
  GERRIT_RPGROUP_ID=$(fetch_property "gerrit.rpgroupid")
  GERRIT_REPO_HOME=$(fetch_property "gerrit.repo.home")
  DELETED_REPO_DIRECTORY=$(fetch_property "deleted.repo.directory")
  GERRIT_EVENTS_PATH=$(fetch_property "gerrit.events.basepath")
  GERRIT_DB_SLAVEMODE_SLEEPTIME=$(fetch_property "gerrit.db.slavement.sleepTime")
  GITMS_REST_PORT=$(fetch_property "jetty.http.port")
  GITMS_SSL_REST_PORT=$(fetch_property "jetty.https.port")
  GERRIT_REPLICATED_EVENTS_SEND=$(fetch_property "gerrit.replicated.events.enabled.send")
  GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL=$(fetch_property "gerrit.replicated.events.enabled.receive.original")
  GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD=$(fetch_property "gerrit.replicated.cache.names.not.to.reload")
  GERRIT_HELPER_SCRIPT_INSTALL_DIR=$(fetch_property "gerrit.helper.scripts.install.directory")
}

## Get a password from the user, blanking the input, places it into the
## USER_PASSWORD env
function get_password() {
  USER_PASSWORD=""
  while true
  do
    read -s -e -p " $1: " USER_PASSWORD
    if [[ ! -z "$USER_PASSWORD" ]]; then
      break
    fi
  done
}

## Reads input until the user specifies a directory which both exists
## and we have read access to.
## $1: String to display in the prompt
## $2: Whether to prompt if we should create the directory
## $3: Need Writable
function get_directory() {

  local create_directory="false"
  local need_writable="true"

  if [[ "$2" == "true" ]]; then
    create_directory="true"
  fi

  if [[ "$3" == "false" ]]; then
    need_writable="false"
  fi

  while true
  do
    INPUT_DIR=$(get_string "$1")

    # If the user does not specify a value starting with "/" 
    # then they are attempting to use a relative path and this is not allowed
    if [[ "$INPUT_DIR" != /* ]]; then
      perr "Relative paths not allowed"
      continue
    fi
    
    INPUT_DIR=$(sanitize_path "$INPUT_DIR")
    
    ## If the directory does not exist and create_directory is true, offer to create it
    if [[ ! -d "$INPUT_DIR" && ! -e "$INPUT_DIR" && "$create_directory" == "true" ]]; then
      local create_dir=$(get_boolean "The directory [ "$INPUT_DIR" ] does not exist, do you want to create it?" "true")

      if [[ "$create_dir" == "true" ]]; then
        mkdir -p "$INPUT_DIR"
        if [[ "$?" == "0" ]]; then
          break
        else
          perr "Directory could not be created"
        fi
      fi
    fi

    if [[ -d "$INPUT_DIR" ]]; then
      if [[ "$need_writable" == "false" ]]; then
        break
      else
        if [[ -w "$INPUT_DIR" ]]; then
          break
        else
          perr "$INPUT_DIR is not writable"
        fi
      fi
    else
      perr "$INPUT_DIR does not exist"
    fi
  done
}

## Reads input until the user specifies an executable which both exists
## and is executable
## $1: String to display in the prompt
## $2: Default option
function get_executable() {

  local default=""

  if [[ ! -z "$2" ]]; then
    default="[$2] "
  fi

  while true
  do
    read -e -p " $1 $default" EXECUTABLE_PATH
    if [[ -z "$EXECUTABLE_PATH" ]]; then
      EXECUTABLE_PATH=$2
    fi

    if [[ -x "$EXECUTABLE_PATH" ]]; then
      break
    else
      echo "" 1>&2
      perr "Path does not exist or is not executable"
      echo "" 1>&2
    fi
  done
}

## Reads input until the user specifies a string which is not empty
## $1: The string to display
## $2: Set to "true" to allow an empty imput
function get_string() {

  local allow_empty_string="false"

  if [[ ! -z "$2" ]]; then
    allow_empty_string=$(echo "$2" | tr '[:upper:]' '[:lower:]')

    if [[ ! "$allow_empty_string" == "true" ]]; then
      allow_empty_string="false"
    fi
  else
    allow_empty_string="false"
  fi

  while true
  do
    read -e -p " $1: " INPUT
    if [[ ! -z "$INPUT" ]]; then
      break
    fi

    if [[ "$allow_empty_string" == "true" ]]; then
      break
    fi
  done

  echo "$INPUT"
}

## Convert Y/y/N/n to boolean equivalents
function to_boolean() {
  local bool=$(echo "$1" | tr '[:lower:]' '[:upper:]')

  if [[ "$bool" == "Y" ]]; then
    echo "true"
  elif [[ "$bool" == "N" ]]; then
    echo "false"
  fi
}

## fetch a true or false from the user when displaying a Y/N prompt
## $1: The string to display
## $2: "true" or "false" default selection
function get_boolean() {

  local option_yes="y"
  local option_no="n"
  local default=$(echo "$2" | tr '[:upper:]' '[:lower:]')

  if [[ "$default" == "true" ]]; then
    option_yes="Y"
  else
    ## force default to false if it's not true
    default="false"
    option_no="N"
  fi

  local option_string="[${option_yes}/${option_no}]"

  while true
  do
    INPUT=$(get_string "$1 $option_string" "true")

    if [[ -z "$INPUT" ]]; then
      echo "$default"
      break
    fi

    INPUT=$(to_boolean "$INPUT")

    if [[ "$INPUT" == "true" || "$INPUT" == "false" ]]; then
      echo "$INPUT"
      break
    fi
  done
}

## Determine the Gerrit Replication Group ID by allowing the user to type in the
## name of the Replication Group in GitMS.
function get_gerrit_replication_group_id() {
  local group_name=$(get_string "Gerrit Replication Group Name")
  local username=$(get_string "GitMS Admin Username")
  get_password "GitMS Admin Password"
  local password="$USER_PASSWORD"
  local tmpFile=$(mktemp --tmpdir="$SCRATCH")

  ## The port GitMS uses will depend on whether SSL is enabled or not.
  if [[ "$SSL_ENABLED" == "true" ]]; then
    local protocol="https://"
    local rest_port="$GITMS_SSL_REST_PORT"
  else
    local protocol="http://"
    local rest_port="$GITMS_REST_PORT"
  fi

  group_name=$(urlencode "$group_name")
  local url="$protocol"
  url+="127.0.0.1:$rest_port/api/replicationGroups/search?groupName=$group_name"

  curl -u "$username:$password" -s -k -f "$url" > "$tmpFile"
  local group_id=$(grep -oPm1 '(?<=<replicationGroupIdentity>)[^<]+' "$tmpFile")
  echo "$group_id"
}

## Check a Gerrit root for a valid gerrit install
function check_gerrit_root() {
  local gerrit_root="$(sanitize_path "$1")"

  ## Make sure that GERRIT_ROOT/etc/gerrit.config exists

  local gerrit_config=${gerrit_root}"/etc/gerrit.config"

  if [[ ! -e "$gerrit_config" ]]; then
    perr "$gerrit_config does not exist, invalid Gerrit Root directory"
    return 1
  fi

  ## Get the location of the gerrit.war file

  ## default location
  GERRIT_WAR_PATH="${gerrit_root}/bin/gerrit.war"
  local container_war_prop=$(GIT_CONFIG="$gerrit_config" git config container.war)

  if [[ ! -z "$container_war_prop" ]]; then
    ## container.war is set, gerrit.war may be elsewhere

    if [[ "$container_war_prop" = /* ]]; then
      ## absolute path
      GERRIT_WAR_PATH="$container_war_prop"
    else
      ## relative path
      GERRIT_WAR_PATH="${GERRIT_ROOT}/${container_war_prop}"
    fi
  fi

  if [[ ! -e "$GERRIT_WAR_PATH" ]]; then
    perr "$GERRIT_WAR_PATH does not exist"
    return 1
  fi
  
  #check the permissions of the gerrit.war file
  if [[ ! -w "$GERRIT_WAR_PATH" ]]; then
    perr "The gerrit.war file is not writable"
    exit 1
  fi



  return 0
}

function upgrading_from_version() {
  local input_version="$1"

  if [[ -z "$input_version" ]]; then
    perr "Empty version passed in when verifying what version we are upgrading from."
    exit 1
  fi

  # Allow partial version matches by using `.*` after `input_version`
  if [[ "$OLD_GERRIT_VERSION" =~ -RP-"$input_version".* ]]; then
    return 0
  fi

  return 1
}

function check_gerrit_version() {
  echo " Checking Gerrit version..."

  ## Check the version of the detected gerrit.war
  OLD_GERRIT_VERSION=$(extract_gerrit_version_from_war "$GERRIT_WAR_PATH")
  # Confirm we actually got the version information.
  # The version might be blank if we have a corrupted jar file or the incorrect java version was used,
  # although this should be unlikely as we check the java version before entering this function.
  if [[ -z "$OLD_GERRIT_VERSION" ]]; then
    perr "Could not determine the version of the gerrit.war file found at $GERRIT_WAR_PATH"
    exit 1
  fi

  if [[ -z "$CURRENT_GERRIT_VERSION" ]]; then
    perr "Could not determine the version of the release.war"
    exit 1
  fi

  # If the OLD_GERRIT_VERSION is found in the previous allowed versions list
  # then treat it as an upgrade.
  if isPreviousVersionAllowed "$OLD_GERRIT_VERSION"; then
    # Historically, REPLICATED_UPGRADE only ever meant upgrading from a replicated system to a replicated system.
    # The semantic meaning here has now changed, it is now additionally being set if any previous allowed vanilla versions exist.
    REPLICATED_UPGRADE="true"
    # Check if it's a major version upgrade.
    local oldMajorVersion="$(get_major_version "$OLD_GERRIT_VERSION")"
    local currentMajorVersion="$(get_major_version "$WD_GERRIT_VERSION")"
    if [[ "$oldMajorVersion" < "$currentMajorVersion" ]]; then
      MAJOR_VERSION_UPGRADE="true"
    fi

    ## check here for NON_INTERACTIVE mode - it requires upgrade
    ## variables to be set
    if [[ "$NON_INTERACTIVE" == "1" ]]; then
      if [[ -z "$UPGRADE" || -z "$UPDATE_REPO_CONFIG" || -z "$RUN_GERRIT_INIT" || -z "$REMOVE_PLUGIN" ]]; then
        echo "" 1>&2
        perr "This install has been detected as an upgrade, but the upgrade flags: "
        echo "" 1>&2
        echo " * UPGRADE" 1>&2
        echo " * UPDATE_REPO_CONFIG" 1>&2
        echo " * RUN_GERRIT_INIT" 1>&2
        echo " * REMOVE_PLUGIN" 1>&2
        echo "" 1>&2
        perr "Have not been set. Non-interactive upgrade requires that these flags are set."
        exit 1
      fi
    fi
  fi

  OLD_BASE_GERRIT_VERSION=$(echo "$OLD_GERRIT_VERSION" | cut -f1 -d"-")

  if [[ ! "$OLD_BASE_GERRIT_VERSION" == "$CURRENT_GERRIT_VERSION" && ! "$REPLICATED_UPGRADE" == "true" ]]; then
    ## Gerrit version we're installing does not match the version already installed
    perr " Gerrit version detected is \"$OLD_BASE_GERRIT_VERSION\" but the Gerrit version should be \
\"$CURRENT_GERRIT_VERSION\", aborting"
    exit 1
  fi
}

function check_java_version() {
  local javaHome="$1"
  local sanitized="$(sanitize_path $javaHome)"

  #Parsing the actual version from the java -version output
  known_java_version="$("$sanitized/bin/java" -version 2>&1 | awk -F '"' '/version/ {print $2}')"
  known_java_version_split=(${known_java_version//./ })

  if [[ "${#known_java_version_split[@]}" -lt 2 || "${known_java_version_split[0]}" -ne $TARGET_JDK_VERSION ]];then
    return 1
  fi
  return 0
}

function set_java_install_location() {
  local installation="$1"
  local sanitized="$(sanitize_path $installation)"
  JAVA_INSTALL_LOCATION="$sanitized"
}

function find_and_set_java() {
  echo " Checking Java version is set..."
  echo ""
  info " We require that you use Java $TARGET_JDK_VERSION with this version of GerritMS."
  info ""
  #Read the javaHome property from gerrit.config and set JAVA_INSTALL_LOCATION accordingly
  GERRIT_CONFIG_JAVA=$(GIT_CONFIG="$GERRIT_ROOT/etc/gerrit.config" git config container.javaHome)

  #Check if it is already the jdk we want in TARGET_JDK_VERSION
  #Order is gerrit.config -> GERRIT_JDK_FOR_UPGRADE(if present) -> JAVA_HOME as this lets the new flag be optional
  #If env already has TARGET_JDK as JAVA_HOME.
  if [[ -n "$GERRIT_CONFIG_JAVA" ]] && check_java_version "$GERRIT_CONFIG_JAVA" ; then
    #JDK is correct version use it
    set_java_install_location "${GERRIT_CONFIG_JAVA}"
    info " Using gerrit.config javaHome of ${JAVA_INSTALL_LOCATION}"

  # No valid jdk in gerrit config use GERRIT_JDK_FOR_UPGRADE if we have it
  elif [[ -n "$GERRIT_JDK_FOR_UPGRADE" ]]; then
    if check_java_version "$GERRIT_JDK_FOR_UPGRADE"; then
      #GERRIT_JDK_FOR_UPGRADE was found and correct version use it
      set_java_install_location "${GERRIT_JDK_FOR_UPGRADE}"
      info " Using provided environment variable GERRIT_JDK_FOR_UPGRADE: ${JAVA_INSTALL_LOCATION}"
    else
      #GERRIT_JDK_FOR_UPGRADE was provided but pointing to wrong jdk version.
      #Report this and let logic for no JDK play out to either prompt or kill install
      info " Not using GERRIT_JDK_FOR_UPGRADE: '${GERRIT_JDK_FOR_UPGRADE}' as it is not a JDK ${TARGET_JDK_VERSION} Installation."
    fi
  #GERRIT_JDK_FOR_UPGRADE wasn't provided and gerrit.config was incorrect. Check if JAVA_HOME is available
  #Will not check JAVA_HOME if the GERRIT_JDK_FOR_UPGRADE was set even if invalid.
  elif [[ -n "$JAVA_HOME" ]] && check_java_version "$JAVA_HOME" ; then
    #JAVA_HOME is a match use it
    set_java_install_location "${JAVA_HOME}"
    info " Using configured JAVA_HOME installation of ${JAVA_INSTALL_LOCATION}"
  fi

  #No valid java installation from any sources.
  if [[ -z "$JAVA_INSTALL_LOCATION" ]]; then
    info " No matching JDK ${TARGET_JDK_VERSION} installations were found."
    #If in interactive mode fire the prompts, if GERRIT_JDK_FOR_UPGRADE or JAVA_HOME were valid installs
    #We don't need to enter this block
    if [[ ! "$NON_INTERACTIVE" == "1" ]]; then
      info " Gerrit.config must be updated."
      info ""
      # Only prompt in interactive mode
      prompt_for_new_java_home
    else
      #In non-interactive mode and we got to here without a valid jdk provided
      if [[ -n "$GERRIT_JDK_FOR_UPGRADE" ]]; then
        #ENV was set and was wrong
        perr " GERRIT_JDK_FOR_UPGRADE value of '${$GERRIT_JDK_FOR_UPGRADE}' is not a valid JDK ${TARGET_JDK_VERSION} installation."
      else
        #ENV GERRIT_JDK_FOR_UPGRADE was not set
        perr " GERRIT_JDK_FOR_UPGRADE has not been exported! Please set to the location of a JDK ${TARGET_JDK_VERSION} installation."
      fi
      perr " Aborting non-interactive installation"
      #Both cases are an exit in non-interactive
      exit 1
    fi
  fi
  #We found a valid JDK installation or provided one in prompts export it for other methods.
  info ""
  export JAVA_INSTALL_LOCATION
  return 0
}

function prompt_for_new_java_home() {
  while true; do
    get_directory "Please enter a new java home for version $TARGET_JDK_VERSION" "false" "false"
      if ! check_java_version "$INPUT_DIR"; then
        perr " Provided path $INPUT_DIR is not a valid JDK $TARGET_JDK_VERSION Installation."
        continue
      fi
      break
  done
  JAVA_INSTALL_LOCATION="$INPUT_DIR"
}

# GERRIT_ROOT must be set to be in non interactive.
function get_gerrit_root_from_user() {
  if [[ -z "$GERRIT_ROOT" ]]; then
    header
    info bold " Configuration Information"
    info ""
    while true
    do
      get_directory "Gerrit Root Directory" "false"

      ## Check the Gerrit install at this location is good
      if ! check_gerrit_root "$INPUT_DIR"; then
        continue
      fi
      break
    done
    GERRIT_ROOT="$INPUT_DIR"
    export GERRIT_ROOT
  else
    info " Gerrit Root Directory: $GERRIT_ROOT"

    ## GERRIT_ROOT is either set in non-interactive mode, or from application.properties
    ## It should still be verified, but in this case, a failure exits the install
    ## rather than re-prompting for input
    if ! check_gerrit_root "$GERRIT_ROOT"; then
      perr "Exiting install, $GERRIT_ROOT does not point to a valid Gerrit install."
      exit 1;
    fi
  fi
}

function setup_new_java_version() {
  # check_java will already have checked it is the correct java version.

  # Get javaHome from the config
  local javaHomeConfig=$(git config -f "$GERRIT_ROOT/etc/gerrit.config" container.javaHome)

  # Update the config to JAVA_INSTALL_LOCATION if it's different.
  if [[ ! "$JAVA_INSTALL_LOCATION" == "$javaHomeConfig" ]]; then
    info true " Updating container.javaHome in $GERRIT_ROOT/etc/gerrit/config from $javaHomeConfig to $JAVA_INSTALL_LOCATION"
    runCommandExitIfFail git config -f "$GERRIT_ROOT/etc/gerrit.config" container.javaHome "$JAVA_INSTALL_LOCATION"
  fi
  return 0
}

# Run during both INTERACTIVE AND NON-INTERACTIVE modes so using echo instead of info
function run_gerrit_init() {
  local javaCommand

  info true ""
  info true bold " Running Gerrit init..."
  info true ""

  print_replicated_upgrade_info

  local ret_code
  javaCommand="$(get_java_command -p init -d "${GERRIT_ROOT}" --batch --reindex-threads=-1)"
  runCommandGetExitCode "$javaCommand"
  ret_code="$?"

  if [[ "$ret_code" -ne "0" ]]; then
    echo "" 1>&2
    perr "Init process failed with return code: ${ret_code}."
    perr "The following command was executed:"
    perr "$javaCommand"
    echo "" 1>&2
    exit 1
  else
    info true ""
    info true bold " Finished init"
  fi
}

function get_java_command() {
  local javaHome="$(sanitize_path $JAVA_INSTALL_LOCATION)"
  local warFile="${GERRIT_ROOT}/bin/gerrit.war"
  local program
  local options=()

  POSITIONAL_ARGS=()
  while [[ $# -gt 0 ]]; do
    case $1 in
      -j|--java-home)
        javaHome="$2"
        shift
        shift
        ;;
      -w|--war-file)
        warFile="$2"
        shift
        shift
        ;;
      -p|--program)
        program="$2"
        shift
        shift
        ;;
      -D*)
        options+=("$1")
        shift
        ;;
      *)
        POSITIONAL_ARGS+=("$1") # save positional arg
        shift # past argument
        ;;
    esac
  done


  echo "$javaHome/bin/java ${options[*]} -jar $warFile $program ${POSITIONAL_ARGS[*]}"
}

# Run during both INTERACTIVE AND NON-INTERACTIVE modes so using echo instead of info
function run_copy_approvals() {
  if [[ "$REPLICATED_UPGRADE" != "true" || "$FIRST_NODE" != "true" ]]; then
    return
  fi

  # Copy approvals is only ran for a replicated upgrade from 1.11
  if ! upgrading_from_version "1.11"; then
    return
  fi

  #  If GERRIT_DISABLE_COPY_APPROVALS is true, skip.
  if [[ "$GERRIT_DISABLE_COPY_APPROVALS" == "true" ]]; then
    info "Skipping copy approvals as GERRIT_DISABLE_COPY_APPROVALS=$GERRIT_DISABLE_COPY_APPROVALS"
    return
  fi

  print_copy_approvals_steps

  info true ""
  info true bold " Running copy approvals..."
  info true ""

  local ret_code
  javaCommand="$(get_java_command -p CopyApprovals -d "${GERRIT_ROOT}")"
  runCommandGetExitCode "$javaCommand"
  ret_code="$?"

  if [[ "$ret_code" -ne "0" ]]; then
    echo "" 1>&2
    perr "Copy approvals process failed with return code: ${ret_code}."
    perr "Copy approvals will have to be run manually by running the command:"
    perr "$javaCommand"
    echo "" 1>&2
  else
    info true ""
    info true bold " Finished copy approvals"
  fi
}

function print_replicated_upgrade_info() {

  if [[ ! "$REPLICATED_UPGRADE" == "true" ]]; then
    return
  fi

  info true ""
  info true bold " Upgrade Detected"
  info true ""
  info true bold " * Running Gerrit Re-init"
  info true ""
  info true "   - You are currently upgrading from GerritMS ${OLD_GERRIT_VERSION} to ${WD_GERRIT_VERSION}"
  info true "   - This requires an upgrade in the database schema to be performed by Gerrit as detailed here:"
  info true "   - ${GERRIT_RELEASE_NOTES}"
  info true ""
  info true "   - The following command will be run automatically during this upgrade: "
  info true bold "   - $(get_java_command init -d "${GERRIT_ROOT}" --batch --reindex-threads=-1)"
  info true ""
  info " Note: This command is automatically run across all nodes being upgraded, even if a replicated/shared"
  info " database is in use. This is required to update locally stored 3rd party dependencies not "
  info " included in the gerrit.war file."
  info ""
}

function print_reindex_steps() {
  info true bold " * Run Gerrit Reindex"
  info true "   - You are currently upgrading from GerritMS ${OLD_GERRIT_VERSION} to ${WD_GERRIT_VERSION}"
  info true "   - As this is the first node you are upgrading, you will need to perform an offline reindex."
  info true ""
  info true bold "   - Run the following command manually "
  info true bold "   - $(get_java_command reindex -d "${GERRIT_ROOT}")"
  info true ""
}

function print_rsync_steps(){
  # For a fresh install, just print it.
  # * The rsync of the GERRIT_ROOT is still needed for a fresh install but this should only be printed for a FIRST_NODE
  # * The repositories may be in a separate location to the GERRIT_ROOT, ensure they are also rsync'd
  if [[ "$REPLICATED_UPGRADE" == "false" ]]; then

    # If the GERRIT_REPO_HOME is not co-located with the GERRIT_ROOT and is located somewhere else, then
    # print that they need to also rsync the GERRIT_REPO_HOME directory also.
    # For example if GERRIT_ROOT=/home/wandisco/gerrit and GERRIT_REPO_HOME is /opt/wandisco/git, then print the extra line
    info true " * rsync $GERRIT_ROOT to all of your GerritMS nodes."
    if [[ "$(realpath -m "$GERRIT_REPO_HOME")" != "$(realpath -m "$GERRIT_ROOT")"* ]]; then
      info true " \t- Please rsync the following directories to all of your GerritMS nodes:"
      info true " \t- ${GERRIT_REPO_HOME}"
    fi
  fi

  # For an upgrade
  if [[ "$REPLICATED_UPGRADE" == "true" ]]; then
    info true " * rsync the following directories to all of your GerritMS nodes."
    info true " \t- ${GERRIT_ROOT}/index"
    info true " \t- ${GERRIT_REPO_HOME}"
  fi
}

function print_copy_approvals_steps() {

  if [[ "$REPLICATED_UPGRADE" != "true" ]]; then
      return
  fi

  info true ""
  info true bold " Gerrit Copy Approvals"
  info true ""
  info true " You are currently upgrading from GerritMS ${OLD_GERRIT_VERSION} to ${WD_GERRIT_VERSION}"
  info true " This requires running copy approvals."
  info true ""
  info true " The following command will be run automatically during this upgrade: "
  info true bold " $(get_java_command -p CopyApprovals -d "${GERRIT_ROOT}")"
  info true ""
}

## arg1 = string to look within.
## arg2 = value to look in arg1 for.
function stringContains() {
  [[ -z "${1##*$2*}" ]] && [[ -z "$2" || -n "$1" ]];
}

function checkForCacheName() {
  if ! stringContains $GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD $table_cache_name;
  then
    ## N.B. Do NOT change this evaluation to anything which spawns a sub process like [ -z $x ] as our
    ## Params will no longer be changed as they are local to the child process. http://tldp.org/LDP/abs/html/subshells.html
    if [[ -z "$GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD" ]]
    then
      GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD=$table_cache_name;
    else
      GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD+=",$table_cache_name";
    fi
  fi
}

function get_config_from_user() {
  header
  info bold " Configuration Information"
  info ""
  find_gitms

  if [[ ! "$NON_INTERACTIVE" == "1" ]]; then
    while true
    do
      read -e -p " Git Multisite root directory$GITMS_ROOT_PROMPT: " INPUT
      if [[ -z "$INPUT" ]]; then
        INPUT=$GITMS_ROOT
      fi

      if [[ -d "$INPUT" && -r "$INPUT" ]]; then
        ## The directory exists, but we must ensure it has an application.properties file
        APPLICATION_PROPERTIES="$INPUT"
        APPLICATION_PROPERTIES+="/replicator/properties/application.properties"

        if [[ ! -e "$APPLICATION_PROPERTIES" ]]; then
          echo "" 1>&2
          perr "$APPLICATION_PROPERTIES cannot be found"
          echo "" 1>&2
          continue
        fi

        break
      else
        echo "" 1>&2
        perr "Directory does not exist or is not readable"
        echo "" 1>&2
      fi
    done

    GITMS_ROOT="$INPUT"

    # Check the main.conf file for the GitMS user and if the current user is not the same
    # then we need to abort the install
    local currentUser=$(id -u -n)
    MAIN_CONF_FILE="$GITMS_ROOT/config/main.conf"

    if [[ ! -r $MAIN_CONF_FILE ]]; then
      perr "Exiting install, main.conf does not exist in the config directory of the GitMS root directory."
      exit 1
    fi

    # To address GER-873, get the umask value from GitMS
    # main.conf, so it can be applied to scripts that are
    # installed with Gerrit.
    umask_value=$(fetch_property_from_main_conf "GITMS_UMASK")
    if [[ -z "$umask_value" ]];then
      perr "Failed to find GITMS_UMASK in GitMS main.conf"
      exit 1
    else
      umask "$umask_value"
    fi
    gitmsUser=$(fetch_property_from_main_conf "GITMS_USER")

    if [[ "$currentUser" != "$gitmsUser" ]]; then
      echo "" 1>&2
      perr "You must run the GitMS and Gerrit services as the same user."
      echo " Current user: $currentUser" 1>&2
      echo " GitMS user: $gitmsUser" 1>&2
      echo " Exiting install" 1>&2
      echo "" 1>&2
      exit 1
    fi
    info ""
    info bold " Reading GitMS Configuration..."
    info ""
    fetch_config_from_application_properties
  fi
  ## Copy APPLICATION_PROPERTIES to SCRATCH, so an install aborted part way
  ## will not have modified existing install.
  cp "$APPLICATION_PROPERTIES" "$SCRATCH"
  TMP_APPLICATION_PROPERTIES="$SCRATCH/application.properties"

  ## This is either an upgrade for an install which has already used GerritMS, or
  ## it is a clean install. Look at the application.properties to determine if
  ## any necessary values are not present. Only offer to set values which are
  ## not already set.
  remove_legacy_config_from_application_properties

  if [[ -z "$GERRIT_ENABLED" ]]; then
    set_property "gerrit.enabled" "true"
  fi

  ## Check if Gerrit is running now that we know the Gerrit root
  if ! check_gerrit_status ; then
    ##Gerrit was detected as running, display a warning
    echo "" 1>&2
    perr "A process has been detected on the Gerrit HTTP port $(get_gerrit_port)."
    perr "Is Gerrit still running? Please make this port available and re-run the installer."
    echo "" 1>&2
    exit 1
  fi

  set_property "gerrit.root" "$GERRIT_ROOT"

  if ps aux | grep GerritCodeReview | grep $GERRIT_ROOT | grep -v " grep " > /dev/null 2>&1; then
    info ""
    info "WARNING: Looks like Gerrit is currently running"
    info ""
  fi

  if [[ ! "$NON_INTERACTIVE" == "1" ]]; then
    if [[ -n "$GERRIT_USERNAME" ]]; then
      info ""
      info bold " Gerrit Admin Username and Password"
      info ""
      REMOVE_UNAME_AND_PASSWD=$(get_boolean "It is no longer necessary to keep your Gerrit Admin Username or Password. Would you like to remove them?" "true")
      if [[ "$REMOVE_UNAME_AND_PASSWD" == "true" ]]; then
        remove_property "gerrit.username" "gerrit.password"
      else
        info " Not removing Gerrit Admin Username and Password."

      fi
    fi
  else
    remove_property "gerrit.username" "gerrit.password"
  fi
  info

  if [[ -z "$GERRIT_REPO_HOME" ]]; then
    get_directory "Gerrit Repository Directory" "false"
    GERRIT_REPO_HOME="$INPUT_DIR"
  else
    info " Gerrit Repository Directory: $GERRIT_REPO_HOME"
  fi

  set_property "gerrit.repo.home" "$GERRIT_REPO_HOME"

  if [[ -z "$GERRIT_EVENTS_PATH" ]]; then
    get_directory "Gerrit Events Directory" "true"
    GERRIT_EVENTS_PATH="$INPUT_DIR"
  else
    info " Gerrit Events Path: $GERRIT_EVENTS_PATH"
  fi

  ## GERRIT_EVENTS_PATH must exist, if it does not attempt to create it
  if [[ ! -d "$GERRIT_EVENTS_PATH" ]]; then
    mkdirectory $GERRIT_EVENTS_PATH
  fi

  set_property "gerrit.events.basepath" "$GERRIT_EVENTS_PATH"

  if [[ -z "$GERRIT_REPLICATED_EVENTS_SEND" ]]; then
    GERRIT_REPLICATED_EVENTS_SEND=$(get_boolean "Will this node send Replicated Events to other Gerrit nodes?" "true")
  else
    info " Gerrit Receive Replicated Events: $GERRIT_REPLICATED_EVENTS_SEND"
  fi

  set_property "gerrit.replicated.events.enabled.send" "$GERRIT_REPLICATED_EVENTS_SEND"


  if [[ -z "$GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL" ]]; then
    GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL=$(get_boolean "Will this node receive Replicated Events from other Gerrit nodes?" "true")
  else
    info " Gerrit Send Replicated Events: $GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL"
  fi

  set_property "gerrit.replicated.events.enabled.receive.original" "$GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL"


  ## Array of caches we do not wish to reload.  We need to deal with fresh install / upgrade scenarios and user changes to this field.
  CACHE_NAMES_NOT_TO_RELOAD=(changes projects groups_byinclude groups_byname groups_byuuid groups_external groups_members groups_bysubgroup groups_bymember)

  if [[ -z "$GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD" ]]; then
    # to avoid keeping 2 lists of same info, just build from our array above.
    printf -v GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD ',%s' "${CACHE_NAMES_NOT_TO_RELOAD[@]}" # yields ",changes,projects,....."
    GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD=${GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD:1} # drop leading comma
  else
    ## Check that an upgraded value contains dont reload group caches.
    for table_cache_name in "${CACHE_NAMES_NOT_TO_RELOAD[@]}"
    do
      checkForCacheName
    done

    info " Gerrit Replicated Cache exclude reload for: $GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD"
  fi
  set_property "gerrit.replicated.cache.names.not.to.reload" "$GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD"

  if [[ -z "$GERRIT_RPGROUP_ID" ]]; then
    if ! is_gitms_running; then
      perr "Looks like Git Multisite is not running"
      perr "Please ensure that Git Multisite is running and re-run the installer."
      exit 1
    fi
    while true
    do
      GERRIT_RPGROUP_ID=$(get_gerrit_replication_group_id)

      if [[ ! -z "$GERRIT_RPGROUP_ID" ]]; then
        info ""
        info " Replication Group found with ID: $GERRIT_RPGROUP_ID"
        break
      else
        echo "" 1>&2
        perr "Could not retrieve Replication Group ID with configuration provided"
        echo "" 1>&2
      fi
    done
  else
    info " Gerrit Replication Group ID: $GERRIT_RPGROUP_ID"
  fi

  set_property "gerrit.rpgroupid" "$GERRIT_RPGROUP_ID"


  if [[ -z "$GERRIT_DB_SLAVEMODE_SLEEPTIME" ]]; then
    set_property "gerrit.db.slavemode.sleepTime" "0"
  fi

  #prompt for directory to save deleted repos to
  if [[ -z "$DELETED_REPO_DIRECTORY" ]]; then
    info ""
    info bold " Deleted Repositories Directory "
    info "
    The Deleted Repositories Directory is only needed if you are using the Gerrit Delete Project Plugin.
    Remember that you should periodically review the repositories that are in the Deleted Repositories Directory
    and physically remove those that are no longer needed. You will need this directory to be capable of storing
    all deleted project's repositories until they can be reviewed and removed."
    info ""

    #provide a default
    DELETED_REPO_DEFAULT_DIRECTORY=$(sanitize_path "$GERRIT_REPO_HOME/archiveOfDeletedGitRepositories")

    while true
    do
      read -e -p " Location for deleted repositories to be moved to [$DELETED_REPO_DEFAULT_DIRECTORY]: " INPUT

      #If the input is empty then set the directory to the default directory
      if [[ -z "$INPUT" ]]; then
        DELETED_REPO_DIRECTORY=$DELETED_REPO_DEFAULT_DIRECTORY
        break
      else
      	DELETED_REPO_DIRECTORY=$(sanitize_path "$INPUT")
      	break
      fi
    done
  else
    info " Deleted Repo Directory: $DELETED_REPO_DIRECTORY"
  fi

  info " Directory is $DELETED_REPO_DIRECTORY"

  ## DELETED_REPO_DIRECTORY must exist, if it does not attempt to create it
  if [[ ! -d "$DELETED_REPO_DIRECTORY" ]]; then
    mkdirectory $DELETED_REPO_DIRECTORY
  fi

  set_property "deleted.repo.directory" $DELETED_REPO_DIRECTORY

  if [[ -z "$GERRIT_HELPER_SCRIPT_INSTALL_DIR" ]]; then

    info ""
    info bold " Helper Scripts"
    info ""
    info " We provide some optional scripts to aid in installation/administration of "
    info " GerritMS. Where should these scripts be installed?"
    info ""

    #provide a default
    GERRIT_HELPER_SCRIPT_DEFAULT_INSTALL_DIR=$(sanitize_path "$GERRIT_ROOT/bin")
    while true
    do
      read -e -p " Helper Script Install Directory [$GERRIT_HELPER_SCRIPT_DEFAULT_INSTALL_DIR]: " INPUT

      #If the input is empty then set the directory to the default directory
      if [[ -z "$INPUT" ]]; then
        INPUT=$GERRIT_HELPER_SCRIPT_DEFAULT_INSTALL_DIR
        GERRIT_HELPER_SCRIPT_INSTALL_DIR=$INPUT
        break
      else
      	INPUT=$(sanitize_path "$INPUT")
        if [[ -d "$INPUT" && -w "$INPUT" ]]; then
          GERRIT_HELPER_SCRIPT_INSTALL_DIR=$INPUT
          break
        else
          echo "" 1>&2
          perr "Directory does not exist or is not writable"
          echo "" 1>&2
          continue
        fi
      fi
    done
  else
    info " Helper Script Install Directory: $GERRIT_HELPER_SCRIPT_INSTALL_DIR"
  fi

  set_property "gerrit.helper.scripts.install.directory" $GERRIT_HELPER_SCRIPT_INSTALL_DIR

}

# Check is git-multisite running by
# calling the git-multisite script and
# invoking a status check.
function is_gitms_running() {
  local init_cmd="${GITMS_ROOT}/bin/git-multisite"
  if [[ -x "${init_cmd}" ]]; then
    if "${init_cmd}" status >/dev/null 2>&1 ; then
      return 0
    fi
  else
    perr "Git Multisite startup script cannot be found or is not executable."
    echo "" 1>&2
  fi
  return 1
}

## Check if a port is currently being used
# $1 port to check
# returns 0 if port isn't in use
function is_port_available() {
  # This command will have a command exit status of 1 whenever
  # a port is free and a command exit status of 0 if port is in use.
  ss -ant | grep '^LISTEN ' | grep ":$1 " > /dev/null 2>&1
  if [[ $? -ne 0 ]]; then
    return 0 # Port is available, return 0
  else
    return 1 # Port is in use, return 1
  fi
}

function check_gerrit_status() {
  local gerrit_port=$(get_gerrit_port)

  if is_port_available "$gerrit_port"; then
    return 0
  else
    return 1
  fi
}

function get_gerrit_port() {
  local gerrit_port=$(GIT_CONFIG="$GERRIT_ROOT/etc/gerrit.config" git config httpd.listenUrl | cut -d":" -f3 | cut -d"/" -f1)
  echo "$gerrit_port"
}

function create_backup() {
  header
  info bold " Backup Information"
  info ""
  info " Taking a backup of the GitMS + Gerrit configuration. Where should this"
  info " be saved?"
  info ""

  if [[ ! "$NON_INTERACTIVE" == "1" ]]; then
    get_directory "Backup Location" "true"
    BACKUP_ROOT="$INPUT_DIR"
  else
    BACKUP_ROOT="$BACKUP_DIR"
  fi

  mkdir -p "$SCRATCH/backup/gerrit"

  if [[ "$FIRST_NODE" == "true" ]]; then
    ## Fetch some properties from gerrit.config to potentially backup the database
    local db_name=$(GIT_CONFIG="$GERRIT_ROOT/etc/gerrit.config" git config database.database)

    info ""
    info " \033[1mNOTE:\033[0m This instance of Gerrit has been configured to use the database $db_name."
    info " It is recommended that you create a backup of this database \033[1mnow\033[0m if one"
    info " does not exist already."
    info ""
  fi

  ## Should backup the GERRIT_ROOT directories, excluding the gerrit.basePath
  ## as well as the GitMS application.properties file before any modifications we
  ## might make to it.
  info " Creating backup..."
  local timestamped_backup=$(date +%Y%m%d%H%M%S)
  timestamped_backup+=".tar.gz"

  local gerrit_base_path=$(get_gerrit_base_path "$GERRIT_ROOT")
  local gerritBackup=$(sanitize_path "${BACKUP_ROOT}/gerrit-backup-${timestamped_backup}")
  local tmpFile=$(mktemp --tmpdir="$SCRATCH")

  if [[ $gerrit_base_path = /* ]]; then
    gerrit_base_path=${gerrit_base_path#$GERRIT_ROOT/}
  fi

  rsync -aq --exclude="$gerrit_base_path" --exclude "db/" --exclude "tmp/" "$GERRIT_ROOT/" "${SCRATCH}/backup/gerrit" > /dev/null 2>&1
  cp "$APPLICATION_PROPERTIES" "${SCRATCH}/backup"

  CMD='tar -zcpf "$gerritBackup" -C "$SCRATCH" "backup"'
  if eval $CMD > /dev/null 2> "${tmpFile}"; then
    : # exited ok
  else
    exval=$?
    perr "Backup command ('$CMD') failed: $exval"
    perr "Backup STDERR was:"
    cat "${tmpFile}" 1>&2
    perr "End of STDERR."
    exit 2
  fi

  info " Backup saved to: \033[1m${gerritBackup}\033[0m"

  info ""
  next_screen " Press [Enter] to Continue"
}

function write_gitms_config() {
  local timestamped=$(date +%Y%m%d%H%M%S)
  cp "$APPLICATION_PROPERTIES" "${APPLICATION_PROPERTIES}.${timestamped}"
  mv "$TMP_APPLICATION_PROPERTIES" "$APPLICATION_PROPERTIES"
  git config --global core.gitmsconfig "$APPLICATION_PROPERTIES"
  info " GitMS Configuration Updated"
}

function write_gerrit_config() {

  local gerrit_config="$GERRIT_ROOT/etc/gerrit.config"
  local tmp_gerrit_config="$SCRATCH/gerrit.config"
  cp "$gerrit_config" "$tmp_gerrit_config"

  GIT_CONFIG="$tmp_gerrit_config" git config receive.timeout 900000

  local timestamped=$(date +%Y%m%d%H%M%S)
  cp "$gerrit_config" "${gerrit_config}.${timestamped}"

  mv "$tmp_gerrit_config" "$gerrit_config"

  info ""
  info " Gerrit Configuration Updated"
}

function replace_gerrit_war() {
  RELEASE_WAR="release.war"
  OLD_WAR="$GERRIT_ROOT/bin/gerrit.war"

  cp "$RELEASE_WAR" "$OLD_WAR"
  if [[ $? -ne 0 ]]; then
    echo "Failed to copy $RELEASE_WAR to $OLD_WAR"
    exit 1
  fi
}

function remove_gitms_gerrit_plugin() {
  local plugin_location="$GERRIT_ROOT/plugins/gitms-gerrit-event-plugin.jar"

  if [[ -e "$plugin_location" ]]; then
    rm -f "$plugin_location"
  fi
}

# This function will make a copy and update the
# gerrit service template by replacing a place
# holder with the actual path to where gerrit has
# been installed.  This will produce a fully
# functional service file.
function update_gerrit_service_template() {
  temp_dir=$(mktemp -d)

  cp -f "gerrit.service.template" "$temp_dir"

  GITMS_REP_SERVICE_FILE="$GITMS_ROOT/systemd/wdgitmsrep.service"
  DEFAULT_VAL=65536
  # Check if the wdgitmsrep.service file exists. If it does then attempt
  # to parse the values for TasksMax, LimitNOFILE and LimitNPROC
  if [[ -e "$GITMS_REP_SERVICE_FILE" ]]; then
      TASKS_MAX=$(sed -n 's/^TasksMax=//p' "$GITMS_REP_SERVICE_FILE")
      LIMIT_NO_FILE=$(sed -n 's/^LimitNOFILE=//p' "$GITMS_REP_SERVICE_FILE")
      LIMIT_N_PROC=$(sed -n 's/^LimitNPROC=//p' "$GITMS_REP_SERVICE_FILE")
  fi

  #If no value found, or property line commented out, then providing a default
  TasksMaxVal=${TASKS_MAX:-"$DEFAULT_VAL"}
  LimitNOFILEVal=${LIMIT_NO_FILE:-"$DEFAULT_VAL"}
  LimitNPROCVal=${LIMIT_N_PROC:-"$DEFAULT_VAL"}

  #Replacing the placeholder variables in the gerrit.service.template before it is
  #copied
  sed -i -e "s/\${TasksMax}/$TasksMaxVal/" "${temp_dir}/gerrit.service.template"
  sed -i -e "s/\${LimitNOFILE}/$LimitNOFILEVal/" "${temp_dir}/gerrit.service.template"
  sed -i -e "s/\${LimitNPROC}/$LimitNPROCVal/" "${temp_dir}/gerrit.service.template"

  sed -i -e 's:${Gerrit_install_directory}:'"${GERRIT_ROOT}"':g' "${temp_dir}/gerrit.service.template"

  # Rename service template
  mv "$temp_dir/gerrit.service.template" "$temp_dir/gerrit-rp.service"

  copy_files_into_place "${GERRIT_ROOT}/bin" "$temp_dir/gerrit-rp.service"
  rm -R ${temp_dir}
}

function install_gerrit_scripts() {

  copy_files_into_place "$GERRIT_HELPER_SCRIPT_INSTALL_DIR" "reindex.sh" "sync_repo.sh"

  CONSOLE_API_JAR="console-api.jar"
  if [[ ! -f "$CONSOLE_API_JAR" ]];then
    perr "$CONSOLE_API_JAR not found"
    exit 1
  else
    copy_files_into_place "$GERRIT_HELPER_SCRIPT_INSTALL_DIR" "$CONSOLE_API_JAR"
  fi
}

function copy_files_into_place() {
  dir_to_copy_file_to=$1 && shift
  cp -f -t "$dir_to_copy_file_to/." "$@"
  if [[ $? -ne 0 ]]; then
    perr "Failed to copy to directory \"$dir_to_copy_file_to\" some/all of the following files: $*"
    exit 1
  fi
}


function finalize_install() {
  header
  info true ""
  info true bold " Finalizing Install"

  write_gerrit_config
  write_gitms_config
  replace_gerrit_war
  install_gerrit_scripts
  update_gerrit_service_template
  create_wd_logging_properties_file
  setup_new_java_version
  run_gerrit_init
  run_copy_approvals
  cleanup

  echo ""
  info true bold " GitMS and Gerrit have now been configured."
  echo ""
  info true bold " Next Steps:"
  echo ""

  if [[ "$FIRST_NODE" == "false" ]]; then
    echo " * If you have rsync'd this Gerrit installation from a previous node"
    echo "   please ensure you have updated the $(sanitize_path "${GERRIT_ROOT}/etc/gerrit.config")"
    echo "   file for this node. In particular, the canonicalWebUrl and database settings should"
    echo "   be verified to be correct for this node."
  else

    echo " The following steps are general purpose in nature and may not be optimal for large"
    echo " deployments where more parallelism will be required to keep maintenance outage windows minimal."
    echo ""
    local syncRepoCmdPath=$(sanitize_path "${GERRIT_HELPER_SCRIPT_INSTALL_DIR}/sync_repo.sh")

    if [[ "$REPLICATED_UPGRADE" == "true" && "$MAJOR_VERSION_UPGRADE" == "true" ]]; then
      # If upgrade, this should only be run on the first node
      print_reindex_steps
    fi

    if [[ "$REPLICATED_UPGRADE" == "false" || "$MAJOR_VERSION_UPGRADE" == "true" ]]; then

      print_rsync_steps

      echo " * On each of your Gerrit nodes, update gerrit.config:"
      echo -e "\t- change the hostname of canonicalURL to the hostname for that node"
      echo -e "\t- ensure that database details are correct."

      echo " * Run this installer on all of your other Gerrit nodes."

      echo " * Restart GitMS on all of your nodes now to finalize the configuration changes"

      echo " * Run ${syncRepoCmdPath} on one node to add any existing"
      echo "   Gerrit repositories to GitMS. Note that even if this is a new install of"
      echo "   Gerrit with no user added repositories, running sync_repo.sh is still"
      echo "   required to ensure that All-Projects and All-Users are properly replicated."

      echo " * When all nodes have been installed, you are ready to start the Gerrit services"
      echo "   across all nodes."
    fi
  fi

  echo ""

  if [[ "$NON_INTERACTIVE" == "1" ]]; then
    echo "Non-interactive install completed"
  fi
}

## Determine the version of a Gerrit war file
function extract_gerrit_version_from_war() {
  local warFile="$1"
  local tmpdir=$(mktemp -d --tmpdir="$SCRATCH")
  local tmpWar=$(mktemp --tmpdir="$tmpdir" "${warFile//*\//}.XXX")
  cp "$warFile" "$tmpWar" >/dev/null 2>&1
  #check_executables already confirmed unzip is present.
  local gerritVersion="$(unzip -q -c "$tmpWar" Documentation/index.html | grep Version | sed 's|Version v||g' | sed 's|<[^>]*>||g')"
  echo "$gerritVersion"
}

function get_major_version() {
  local version="$1"
  echo "${version//*-RP-}" | cut -f1,2 -d'.'
}

## Cleanup installation temp files
function cleanup() {
  if [[ ! -z "$SCRATCH" && -d "$SCRATCH" ]]; then
    rm -rf "$SCRATCH"
  fi
  if [[ "$REPLICATED_UPGRADE" == "true" ]]; then
    remove_obsolete_dirs
    remove_all_gerrit_event_files
  fi
}

function create_wd_logging_properties_file(){

  if [[ ! -f $GERRIT_ROOT/etc/wd_logging.properties ]]; then

    cat >$GERRIT_ROOT/etc/wd_logging.properties <<EOL
    # @Copyright 2020 WANdisco

    #The wd_logging.properties file is used to change WANdisco specific default logging.
    #The default logging can be changed before startup to allow for custom logging.

    #The properties contained within the file should be key-value pairs separated by an equals char.
    #The key should be a WANdisco specific package or class and the value should be a
    #logging level you wish to define for that package and optionaly class.
    #Example logging levels are INFO, WARN and DEBUG

    #Example usage;
                 #Package.Class = Logging Level
                 #com.google.gerrit = INFO
                 #com.google.gerrit.server.replication.Replicator = DEBUG
EOL
  else
    info " wd_logging.properties already created."
  fi
}

## GER-583
## Remove obsolete files on upgrade no longer in use by gerrit
function remove_obsolete_dirs() {
  echo " Removing obsolete directories..."
  for odir in "${GERRIT_EVENTS_PATH}/replicated_events/failed_retry" \
              "${GERRIT_EVENTS_PATH}/replicated_events/gen_events" \
              "${GERRIT_EVENTS_PATH}/replicated_events/failed_definitely"; do
      if [[ -d "${odir}" ]]; then
        if rm -rf "${odir}"; then
          echo "Removed obsolete directory: \"${odir}\" during cleanup phase."
        else
          echo "Failed to remove obsolete directory: \"${odir}\" during cleanup phase. Please removed manually."
        fi
      fi
  done
}

## GER-562
## Delete replicated events json files during upgrade
function remove_all_gerrit_event_files() {
  tf="/tmp/${myname}_tf_$$"
  file_total=$(find "$GERRIT_EVENTS_PATH/replicated_events/" -type f -print | tee ${tf}| wc -l)

  echo " Checking for extra files in the gerrit events directory..."
  if [[ $file_total -eq 0 ]]; then
    echo " No files requiring cleanup."
  else
    echo " Found ${file_total} files in gerrit events directory, these will be deleted now..."

    while read afile; do
      if rm -f "${afile}"; then
        echo "Removed file \"${afile}\""
      else
        echo " Failed to remove file \"${afile}\" during cleanup phase. Please remove manually."
      fi
    done < ${tf}
  fi

  rm -f ${tf}
}

## Check for non-interactive mode. If all the required environment variables are set,
## we can skip asking questions

## How many env variables are needed to initiate a non-interactive install
## depends on what properties are already set in application.properties.
## If a property is set there, it is not required as an env variable. Otherwise
## it is.

## Additionally, sanity checking is done here to make sure that application.properties
## and GERRIT_ROOT exist
function check_for_non_interactive_mode() {

  ## These properties will not be set in application.properties
  if [[ ! -z "$GITMS_ROOT" && ! -z "$BACKUP_DIR" && ! -z "$GERRIT_HELPER_SCRIPT_INSTALL_DIR" && ! -z "$FIRST_NODE" && ! -z "$CURL_ENVVARS_APPROVED" ]]; then

    APPLICATION_PROPERTIES="$GITMS_ROOT"
    APPLICATION_PROPERTIES+="/replicator/properties/application.properties"

    if [[ ! -e "$APPLICATION_PROPERTIES" ]]; then
      perr "Non-interactive installation aborted, the file $APPLICATION_PROPERTIES does not exist"
      exit 1
    fi

    ## Need to fetch values from application.properties first, as they should take
    ## precedence over env variables
    local tmp_gerrit_root=$(fetch_property "gerrit.root")
    local tmp_gerrit_rpgroup_id=$(fetch_property "gerrit.rpgroupid")
    local tmp_gerrit_repo_home=$(fetch_property "gerrit.repo.home")
    local tmp_deleted_repo_directory=$(fetch_property "deleted.repo.directory")
    local tmp_gerrit_events_path=$(fetch_property "gerrit.events.basepath")
    local tmp_gerrit_replicated_events_send=$(fetch_property "gerrit.replicated.events.enabled.send")
    local tmp_gerrit_replicated_events_receive_original=$(fetch_property "gerrit.replicated.events.enabled.receive.original")
    local tmp_gerrit_replicated_cache_names_not_to_reload=$(fetch_property "gerrit.replicated.cache.names.not.to.reload")
    local tmp_gerrit_helper_script_install_directory=$(fetch_property "gerrit.helper.scripts.install.directory")

    ## Override env variables where the property already exists
    if [[ ! -z "$tmp_gerrit_root" ]]; then
      GERRIT_ROOT="$tmp_gerrit_root"
    fi

    if [[ ! -z "$tmp_gerrit_rpgroup_id" ]]; then
      GERRIT_RPGROUP_ID="$tmp_gerrit_rpgroup_id"
    fi

    if [[ ! -z "$tmp_gerrit_repo_home" ]]; then
      GERRIT_REPO_HOME="$tmp_gerrit_repo_home"
    fi

    if [[ ! -z "$tmp_deleted_repo_directory" ]]; then
      DELETED_REPO_DIRECTORY="$tmp_deleted_repo_directory"
    fi


    if [[ ! -z "$tmp_gerrit_events_path" ]]; then
      GERRIT_EVENTS_PATH="$tmp_gerrit_events_path"
    fi

    if [[ ! -z "$tmp_gerrit_replicated_events_send" ]]; then
      GERRIT_REPLICATED_EVENTS_SEND="$tmp_gerrit_replicated_events_send"
    fi

    if [[ ! -z "$tmp_gerrit_replicated_events_receive_original" ]]; then
      GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL="$tmp_gerrit_replicated_events_receive_original"
    fi


    if [[ ! -z "$tmp_gerrit_replicated_cache_names_not_to_reload" ]]; then
      GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD="$tmp_gerrit_replicated_cache_names_not_to_reload"
    fi

    if [[ ! -z "$tmp_gerrit_helper_script_install_directory" ]]; then
      GERRIT_HELPER_SCRIPT_INSTALL_DIR="$tmp_gerrit_helper_script_install_directory"
    fi

    ## Check that all variables are now set to something
    if [[ ! -z "$GERRIT_ROOT" && ! -z "$GERRIT_RPGROUP_ID"
      && ! -z "$GERRIT_REPO_HOME" && ! -z "$GERRIT_EVENTS_PATH"
      && ! -z "$DELETED_REPO_DIRECTORY" && ! -z "$GERRIT_REPLICATED_EVENTS_SEND"
      && ! -z "$GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL"
      && ! -z "$GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD" && ! -z "$GERRIT_HELPER_SCRIPT_INSTALL_DIR" ]]; then

      ## On an upgrade, some extra variables must be set. If they are not, non-interactive
      ## mode will not be set
      if [[ -n "$UPGRADE" ]]; then
        if [[ -z "$RUN_GERRIT_INIT" || -z "$UPDATE_REPO_CONFIG" || -z "$REMOVE_PLUGIN" ]]; then
          ## not non-interactive, need to set upgrade variables
          NON_INTERACTIVE=0
          return
        fi

        # If GERRIT_JDK_FOR_UPGRADE is set, then the path to the JDK must exist.
        if [[ -n "$GERRIT_JDK_FOR_UPGRADE" ]]; then
          if [[ ! -d "$GERRIT_JDK_FOR_UPGRADE" ]]; then
            perr "Non-interactive upgrade aborted, the path set for GERRIT_JDK_FOR_UPGRADE: $GERRIT_JDK_FOR_UPGRADE does not exist."
            exit 1
          fi
        fi
      fi

      ## GERRIT_ROOT must exist as well
      if [[ ! -d "$GERRIT_ROOT" ]]; then
        perr "Non-interactive installation aborted, the GERRIT_ROOT at $GERRIT_ROOT does not exist"
        exit 1
      fi

      ## CURL_ENVVARS_APPROVED must be either true or false
      if [[ ! "$CURL_ENVVARS_APPROVED" == "true" ]] && [ ! "$CURL_ENVVARS_APPROVED" == "false" ]; then
        perr "Non-interactive installation aborted, the CURL_ENVVARS_APPROVED must be either \"true\" or \"false\". Currently is \"$CURL_ENVVARS_APPROVED\""
        exit 1
      fi

      NON_INTERACTIVE=1

      ## DELETED_REPO_DIRECTORY must exist, if it does not attempt to create it
      if [[ ! -d "$DELETED_REPO_DIRECTORY" ]]; then
        mkdirectory $DELETED_REPO_DIRECTORY
      fi

      ## GERRIT_EVENTS_PATH must exist, if it does not attempt to create it
      if [[ ! -d "$GERRIT_EVENTS_PATH" ]]; then
        mkdirectory $GERRIT_EVENTS_PATH
      fi
    fi
  fi
}

# Returns 0 if comp_version is less than base_version
# Return 1 if comp_version is greater than or equal to base_version
function versionLessThan() {
  local base_version=$1
  local comp_version=$2

  test "$base_version" = "$comp_version" && return 1

  ## Strip the build number out of the version string, they should
  ## not be included in a version check by default
  if [[ $base_version =~ "-" ]] && [[ $comp_version =~ "-" ]]; then
    base_version_buildno=${base_version#*-}
    comp_version_buildno=${comp_version#*-}
  else
    ## one of the versions doesn't have a build number
    base_version_buildno=0
    comp_version_buildno=0
  fi

  base_version=${base_version%%-*}
  comp_version=${comp_version%%-*}

  local split_base=(${base_version//\./ })
  local split_comp=(${comp_version//\./ })

  local upper=
  if test ${#split_base[@]} -gt ${#split_comp[@]}; then
    upper=${#split_base[@]}
  else
    upper=${#split_comp[@]}
  fi

  local i=
  for ((i=0; i<$upper; i++)); do
    test ${split_comp[$i]:-0} -lt ${split_base[$i]:-0} && return 0
    test ${split_comp[$i]:-0} -gt ${split_base[$i]:-0} && return 1
  done

  ## No return by this point means the versions are the same, compare
  ## the build number extracted earlier
  test $comp_version_buildno -lt  $base_version_buildno && return 0
  test $comp_version_buildno -gt  $base_version_buildno && return 1
  return 1
}

function mkdirectory(){

    local create_dir=true

    if [[ ! "$NON_INTERACTIVE" == "1" ]]; then
	  create_dir=$(get_boolean "The directory [ "$1" ] does not exist, do you want to create it?" "true")
    fi

    if [[ "$create_dir" == "true" ]]; then
      mkdir -p "$1"
      if [[ "$?" != "0" ]]; then
        perr "The directory $1 cannot be created"
        exit 1
      fi
    fi
}

function check_db_config() {
  info bold " Checking Gerrit configuration..."

  # By default the following noteDb configuration is NOT set in the gerrit.config. The checks we are making here
  # are in the case whereby a user decides to go against the recommendation to not set these config options manually.
  # In general, users should NOT set the options described below manually;
  # Please refer to the following documentation:
  # https://gerrit-documentation.storage.googleapis.com/Documentation/3.7.4/note-db.html#_configuration

  # Change data is written to and read from NoteDb, but ReviewDb is still the source of truth.
  local notedb_changes_write=$(GIT_CONFIG="$GERRIT_ROOT/etc/gerrit.config" git config noteDb.changes.write)
  local notedb_changes_read=$(GIT_CONFIG="$GERRIT_ROOT/etc/gerrit.config" git config noteDb.changes.read)

  #  New changes are written only to NoteDb, but changes whose primary storage is ReviewDb are still supported.
  #  Continues to read from ReviewDb first as in the previous stage, but if the change is not in ReviewDb, falls back to reading from NoteDb.
  local notedb_primaryStorage=$(GIT_CONFIG="$GERRIT_ROOT/etc/gerrit.config" git config noteDb.changes.primaryStorage)
  local notedb_disableReviewDb=$(GIT_CONFIG="$GERRIT_ROOT/etc/gerrit.config" git config noteDb.changes.disableReviewDb)

 # If any of these configurations are set in the gerrit.config then check that they have the desired value.
  if [[ -n "$notedb_changes_write"  && "$notedb_changes_write" != true ||
        -n "$notedb_changes_read" && "$notedb_changes_read" != true ||
        -n "$notedb_primaryStorage" && ! "${notedb_primaryStorage,,}" =~ note( |_)db ||
        -n "$notedb_disableReviewDb" && "$notedb_disableReviewDb" != true ]]; then
      perr "Unable to proceed as invalid configuration has been supplied. Detected invalid NoteDB configuration \nNoteDB config should match below:\n[noteDb \"changes\"]\n    disableReviewDb = true\n    primaryStorage = NOTE_DB\n    read = true\n    write = true\n\n
NOTE: It is recommended that these noteDb configuration options are not set manually in the gerrit.config"
      exit 1
  fi
}

TARGET_JDK_VERSION=17

NON_INTERACTIVE=0
check_for_non_interactive_mode

check_executables

REPLICATED_UPGRADE="false"
MAJOR_VERSION_UPGRADE="false"
## Versions of Gerrit that we allow the user to upgrade from. Generally a user is not allowed to skip a major
## version, but can skip minor versions. This is not a hard and fast rule however, as the reality of when an
## upgrade can be safely skipped is down to Gerrit upgrade behaviour. This should have all the release versions
## of the previous major version number, and any release versions of the current major version number.
PREVIOUS_ALLOWED_RP_GERRIT_VERSIONS=("1.11" "1.12" "1.14")
PREVIOUS_ALLOWED_VANILLA_GERRIT_VERSIONS=()
GERRIT_RELEASE_NOTES="https://www.gerritcodereview.com/3.9.html"
GERRITMS_INSTALL_DOC="https://docs.cirata.com/gerrit/3.9/#_installation_guide"

# This extracts the version from the release.war after it is extracted from the gerritms-installer.sh
WD_GERRIT_VERSION=$(extract_gerrit_version_from_war "release.war")
CURRENT_GERRIT_VERSION=$(echo "$WD_GERRIT_VERSION" | cut -f1 -d '-')
prereqs
get_gerrit_root_from_user
check_gerrit_version
find_and_set_java
check_db_config
create_scratch_dir


SPINNER=("|" "/" "-" "\\")

if [[ "$NON_INTERACTIVE" == "1" ]]; then
  info true bold " Starting non-interactive install of GerritMS..."
  echo ""
  info true bold " Using settings: "
  echo " GITMS_ROOT: $GITMS_ROOT"
  echo " GERRIT_ROOT: $GERRIT_ROOT"
  echo " GERRIT_RPGROUP_ID: $GERRIT_RPGROUP_ID"
  echo " GERRIT_REPO_HOME: $GERRIT_REPO_HOME"
  echo " DELETED_REPO_DIRECTORY: $DELETED_REPO_DIRECTORY"
  echo " GERRIT_EVENTS_PATH: $GERRIT_EVENTS_PATH"
  echo " GERRIT_REPLICATED_EVENTS_SEND: $GERRIT_REPLICATED_EVENTS_SEND"
  echo " GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL: $GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL"
  echo " GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD: $GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD"
  echo " BACKUP_DIR: $BACKUP_DIR"
  echo " GERRIT_HELPER_SCRIPT_INSTALL_DIR: $GERRIT_HELPER_SCRIPT_INSTALL_DIR"
  echo " FIRST_NODE: $FIRST_NODE"
  echo " CURL_ENVVARS_APPROVED: $CURL_ENVVARS_APPROVED"

  [[ -n "$UPGRADE" ]] && UPGRADE=$(echo "$UPGRADE" | tr '[:upper:]' '[:lower:]')
  echo

  if [[ "$UPGRADE" == "true" ]]; then
    info true bold " Upgrade settings:"
    echo " UPGRADE: $UPGRADE"
    echo " UPDATE_REPO_CONFIG: $UPDATE_REPO_CONFIG"
    echo " RUN_GERRIT_INIT: $RUN_GERRIT_INIT"
    echo " REMOVE_PLUGIN: $REMOVE_PLUGIN"
    if [[ -n "${GERRIT_JDK_FOR_UPGRADE}" ]]; then
      echo " GERRIT_JDK_FOR_UPGRADE: $GERRIT_JDK_FOR_UPGRADE"
    fi
  fi
fi

check_environment_variables_that_affect_curl
check_user
get_config_from_user
create_backup
finalize_install
