#!/bin/bash

function info() {
  if [ ! "$NON_INTERACTIVE" == "1" ]; then
    echo -e "$1"
  fi
}

## $1 version to check if allowed
function versionAllowed() {
  local check_version="$1"

  for var in "${PREVIOUS_ALLOWED_RP_GERRIT_VERSIONS[@]}"
  do
    if [ "$var" == "$check_version" ]; then
      return 0
    fi
  done

  return 1
}

function header() {
  if [ ! "$NON_INTERACTIVE" == "1" ]; then
    clear
    cat "resources/logo.txt"
    info "\n\n"
  fi
}

function bold {
  local message="$1"
  info "\033[1m$message\033[0m"
}

function next_screen() {
  if [ ! "$NON_INTERACTIVE" == "1" ]; then
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
    export GIT_CONFIG="${1}/etc/gerrit.config"
    local gerrit_base_path=$(git config gerrit.basePath)
    unset GIT_CONFIG
    echo "$gerrit_base_path"
}

function prereqs() {
  header

  bold " GerritMS Version: $WD_GERRIT_VERSION Installation"
  info ""
  bold " Install Documentation: "
  info ""
  info " $GERRITMS_INSTALL_DOC"
  info ""
  info " Welcome to the GerritMS installation. Before the install can continue,"
  info " you must:"
  info ""
  info " * Have one of the following gerrit versions installed before beginning:"
  info "     - Gerrit: $NEW_GERRIT_VERSION"
  for version in "${PREVIOUS_ALLOWED_RP_GERRIT_VERSIONS[@]}"; do
    info "     - Gerrit MS: $version"
  done
  info " * Have backed up your existing Gerrit database"
  info " * Have a version of GitMS (1.9.1 or higher) installed and running"
  info " * Have a replication group created in GitMS containing all Gerrit nodes"
  info " * Have a valid GitMS admin username/password"
  info " * Stop the Gerrit service on this node"
  info ""

  if [ ! "$NON_INTERACTIVE" == "1" ]; then
    REQUIREMENTS_MET=$(get_boolean "Do you want to continue with the installation?" "true")
    info
    if [ "$REQUIREMENTS_MET" == "false" ]; then
      info "Installation aborted by user"
      exit 0
    fi

    FIRST_NODE=$(get_boolean "Is this the first node GerritMS will be installed to?" "true")
  fi

}

function check_environment_variables_that_affect_curl() {
    if [ -n "$HTTP_PROXY" ] || [ -n "$HTTPS_PROXY" ] || [ -n "$FTP_PROXY" ] || [ -n "$ALL_PROXY" ] || [ -n "$NO_PROXY" ]; then
      info ""
      info " The following environment variables are set and will affect the use of 'curl': "
      info ""
      [ -n "$HTTP_PROXY" ] && echo " * HTTP_PROXY=$HTTP_PROXY"
      [ -n "$HTTPS_PROXY" ] && echo " * HTTPS_PROXY=$HTTPS_PROXY"
      [ -n "$FTP_PROXY" ] && echo " * FTP_PROXY=$FTP_PROXY"
      [ -n "$ALL_PROXY" ] && echo " * ALL_PROXY=$ALL_PROXY"
      [ -n "$NO_PROXY" ] && echo " * NO_PROXY=$NO_PROXY"
      info ""

      if [ -z "$CURL_ENVVARS_APPROVED" ]; then
        CURL_ENVVARS_APPROVED=$(get_boolean "Do you want to continue with the installation?" "true")
      fi
      if [ "$CURL_ENVVARS_APPROVED" == "false" ]; then
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

  if [ "$EUID" -eq 0 ]; then
    info " \033[1mWARNING:\033[0m It is strongly advised that the GitMS and Gerrit services"
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
  if [ $? -ne 0 ]; then
    if [ "$bins" -eq 0 ]; then
      header
    fi
    info " \033[1mmktemp\033[0m version does not support --tmpdir switch, please install the correct version"
    info ""
    ((bins++))
  fi
  if [ "$bins" -ne 0 ]; then
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
  GIT_CONFIG="$HOME/.gitconfig"
  export GIT_CONFIG
  local gitms_config=$(git config core.gitmsconfig)

  if [[ -z "$gitms_config" || ! -e "$gitms_config" ]]; then
    ## check if the default gitms install folder is present
    if [ -d "/opt/wandisco/git-multisite" ]; then
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

## With the GitMS root location, we can look up a lot of information
## and avoid asking the user questions
function fetch_config_from_application_properties() {
  SSL_ENABLED=$(fetch_property "ssl.enabled")
  GERRIT_ENABLED=$(fetch_property "gerrit.enabled")
  GERRIT_ROOT=$(fetch_property "gerrit.root")
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
  GERRIT_REPLICATED_EVENTS_RECEIVE_DISTINCT=$(fetch_property "gerrit.replicated.events.enabled.receive.distinct")
  GERRIT_REPLICATED_EVENTS_LOCAL_REPUBLISH_DISTINCT=$(fetch_property "gerrit.replicated.events.enabled.local.republish.distinct")
  GERRIT_REPLICATED_EVENTS_DISTINCT_PREFIX=$(fetch_property "gerrit.replicated.events.distinct.prefix")
  GERRIT_REPLICATED_CACHE_ENABLED=$(fetch_property "gerrit.replicated.cache.enabled")
  GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD=$(fetch_property "gerrit.replicated.cache.names.not.to.reload")
}

## Get a password from the user, blanking the input, places it into the
## USER_PASSWORD env
function get_password() {
  USER_PASSWORD=""
  while true
  do
    read -s -e -p " $1: " USER_PASSWORD
    if [ ! -z "$USER_PASSWORD" ]; then
      break
    fi
  done
}

## Reads input until the user specifies a directory which both exists
## and we have read access to.
## $1: String to display in the prompt
## $2: Whether to prompt if we should create the directory
function get_directory() {

  local create_directory="false"

  if [ "$2" == "true" ]; then
    create_directory="true"
  fi

  while true
  do
    INPUT_DIR=$(get_string "$1")
    INPUT_DIR=$(sanitize_path "$INPUT_DIR")

    ## If the directory does not exist and create_directory is true, offer to create it
    if [[ ! -d "$INPUT_DIR" && ! -e "$INPUT_DIR" && "$create_directory" == "true" ]]; then
      local create_dir=$(get_boolean "The directory [ "$INPUT_DIR" ] does not exist, do you want to create it?" "true")

      if [ "$create_dir" == "true" ]; then
        mkdir -p "$INPUT_DIR"
        if [ "$?" == "0" ]; then
          break
        else
          echo " ERROR: Directory could not be created"
        fi
      fi
    fi

    if [ -d "$INPUT_DIR" ]; then
      if [ -w "$INPUT_DIR" ]; then
        break
      else
        echo " ERROR: $INPUT_DIR is not writable"
      fi
    else
      echo " ERROR: $INPUT_DIR does not exist"
    fi
  done
}

## Reads input until the user specifies an executable which both exists
## and is executable
## $1: String to display in the prompt
## $2: Default option
function get_executable() {

  local default=""

  if [ ! -z "$2" ]; then
    default="[$2] "
  fi

  while true
  do
    read -e -p " $1 $default" EXECUTABLE_PATH
    if [ -z "$EXECUTABLE_PATH" ]; then
      EXECUTABLE_PATH=$2
    fi

    if [ -x "$EXECUTABLE_PATH" ]; then
      break
    else
      info ""
      info " \033[1mERROR:\033[0m path does not exist or is not executable"
      info ""
    fi
  done
}

## Reads input until the user specifies a string which is not empty
## $1: The string to display
## $2: Set to "true" to allow an empty imput
function get_string() {

  local allow_empty_string="false"

  if [ ! -z "$2" ]; then
    allow_empty_string=$(echo "$2" | tr '[:upper:]' '[:lower:]')

    if [ ! "$allow_empty_string" == "true" ]; then
      allow_empty_string="false"
    fi
  else
    allow_empty_string="false"
  fi

  while true
  do
    read -e -p " $1: " INPUT
    if [ ! -z "$INPUT" ]; then
      break
    fi

    if [ "$allow_empty_string" == "true" ]; then
      break
    fi
  done

  echo "$INPUT"
}

## Convert Y/y/N/n to boolean equivalents
function to_boolean() {
  local bool=$(echo "$1" | tr '[:lower:]' '[:upper:]')

  if [ "$bool" == "Y" ]; then
    echo "true"
  elif [ "$bool" == "N" ]; then
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

  if [ "$default" == "true" ]; then
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

    if [ -z "$INPUT" ]; then
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
  if [ "$SSL_ENABLED" == "true" ]; then
    local protocol="https://"
    local rest_port="$GITMS_SSL_REST_PORT"
  else
    local protocol="http://"
    local rest_port="$GITMS_REST_PORT"
  fi

  group_name=$(urlencode "$group_name")
  local url="$protocol"
  url+="127.0.0.1:$rest_port/api/replication-groups/search?groupName=$group_name"

  curl -u "$username:$password" -s -k -f "$url" > "$tmpFile"
  local group_id=$(grep -oPm1 '(?<=<replicationGroupIdentity>)[^<]+' "$tmpFile")
  echo "$group_id"
}

## Check a Gerrit root for a valid gerrit install
function check_gerrit_root() {
  local gerrit_root="$1"

  ## Make sure that GERRIT_ROOT/etc/gerrit.config exists
 
  local gerrit_config="$(sanitize_path "${gerrit_root}/etc/gerrit.config")"

  if [ ! -e "$gerrit_config" ]; then
    echo "ERROR: $gerrit_config does not exist, invalid Gerrit Root directory"
    return 1
  fi

  ## Get the location of the gerrit.war file

  ## default location
  local gerrit_war_path="${gerrit_root}/bin/gerrit.war"
  GIT_CONFIG="$gerrit_config"
  export GIT_CONFIG
  local container_war_prop=$(git config container.war)

  if [ ! -z "$container_war_prop" ]; then
    ## container.war is set, gerrit.war may be elsewhere

    if [[ "$container_war_prop" = /* ]]; then
      ## absolute path
      gerrit_war_path="$container_war_prop"
    else
      ## relative path
      gerrit_war_path="${GERRIT_ROOT}/${container_war_prop}"
    fi
  fi

  if [ ! -e "$gerrit_war_path" ]; then
    echo " ERROR: $gerrit_war_path does not exist"
    return 1
  fi

  ## Check the version of the detected gerrit.war
  OLD_GERRIT_VERSION=$(get_gerrit_version "$gerrit_war_path")
  if versionAllowed "$OLD_GERRIT_VERSION"; then
    REPLICATED_UPGRADE="true"

    ## check here for NON_INTERACTIVE mode - it requires upgrade
    ## variables to be set
    if [ "$NON_INTERACTIVE" == "1" ]; then
      if [[ -z "$UPGRADE" || -z "$UPDATE_REPO_CONFIG" || -z "$RUN_GERRIT_INIT" || -z "$REMOVE_PLUGIN" ]]; then
        echo ""
        echo "Error: This install has been detected as an upgrade, but the upgrade flags: "
        echo ""
        echo " * UPGRADE"
        echo " * UPDATE_REPO_CONFIG"
        echo " * RUN_GERRIT_INIT"
        echo " * REMOVE_PLUGIN"
        echo ""
        echo "have not been set. Non-interactive upgrade requires that these flags are set."
        exit 1
      fi
    fi
  fi
  OLD_BASE_GERRIT_VERSION=$(echo "$OLD_GERRIT_VERSION" | cut -f1 -d"-")

  if [[ ! "$OLD_BASE_GERRIT_VERSION" == "$NEW_GERRIT_VERSION" && ! "$REPLICATED_UPGRADE" == "true" ]]; then
    ## Gerrit version we're installing does not match the version already installed
    echo -e " \033[1mERROR:\033[0m Gerrit version detected at this location is at version: $OLD_BASE_GERRIT_VERSION"
    echo " The current Gerrit version should be: $NEW_GERRIT_VERSION"
    return 1
  fi

  return 0
}

function replicated_upgrade() {

  if [ ! "$REPLICATED_UPGRADE" == "true" ]; then
    return
  fi

  info ""
  bold " Upgrade Detected"
  info ""
  bold " Gerrit Re-init"
  info ""
  info " You are currently upgrading from WANDisco GerritMS ${OLD_GERRIT_VERSION} to ${WD_GERRIT_VERSION}"
  info " This requires an upgrade in the database schema to be performed by Gerrit as detailed here:"
  info " ${GERRIT_RELEASE_NOTES}"
  info ""
  info " This will require running the command with the format: "
  bold "   java -jar gerrit.war init -d site_path"
  info ""
  info " Note: This command must be run across all nodes being upgraded, even if a replicated/shared"
  info " database is in use. This is required to update locally stored 3rd party dependencies not "
  info " included in the gerrit.war file."
  info ""

  local perform_init

  if [ ! "$NON_INTERACTIVE" == "1" ]; then
    perform_init=$(get_boolean "Do you want this installer to run the re-init command above now?" "true")
  else
    if [ "$RUN_GERRIT_INIT" == "true" ]; then
      perform_init="true"
    else
      perform_init="false"
    fi
  fi

  if [ "$perform_init" == "true" ]; then
    info ""
    info " Running Gerrit re-init..."
    info ""

    ## Find Java - it should be referenced by JAVA_HOME
    if [[ -z "$JAVA_HOME"  || ! -d "$JAVA_HOME" || ! -x "${JAVA_HOME}/bin/java" ]]; then
      bold " Could not find java using JAVA_HOME."
      info ""
      info " Please provide the full path to the java executable you wish to use to perform the re-init."
      info ""

      ## Search for java on the path first, so if we find it we can offer it as a default option
      local detected_java_path=$(command -v java)
      get_executable "Path to Java:" "$detected_java_path"
      JAVA_BIN="$EXECUTABLE_PATH"
      info ""
    else
      ## JAVA_HOME is set and seems to be sane
      JAVA_BIN="${JAVA_HOME}/bin/java"
    fi

    local ret_code
    $JAVA_BIN -jar "${GERRIT_ROOT}/bin/gerrit.war" init -d "${GERRIT_ROOT}" --batch
    ret_code="$?"

    if [ "$ret_code" -ne "0" ]; then
      info ""
      info " \033[1mWARNING:\033[0m Re-init process failed with return code: \033[1m${ret_code}\033[0m."
      info " The re-init will have to be performed manually."
      info ""
    else
      info ""
      info " Finished re-init"
    fi
  fi
}

## Look for all the repos under the git/ folder defined in gerrit.config
## that have replicated = true and denyNonFastForward = true. Offer to
## initiate a series of proposals that will replicate the configuration
## change to this and other nodes.
function scan_repos() {
  local repoPath
  REPO_LIST=()
  GERRIT_GIT_BASE=$(git config -f "$GERRIT_ROOT/etc/gerrit.config" --get gerrit.basepath)

  if [[ "$GERRIT_GIT_BASE" != /* ]]
  then
     #Assumes relative path if GERRIT_GIT_BASE doesn't start with '/'
     GERRIT_GIT_BASE="$GERRIT_ROOT/$GERRIT_GIT_BASE"
  fi

  [ ! -e "$GERRIT_GIT_BASE" ] && die "$GERRIT_GIT_BASE does not exist"
  [ ! -d "$GERRIT_GIT_BASE" ] && die "$GERRIT_GIT_BASE is not a directory"

  info ""
  bold " Scanning for repositories in $GERRIT_GIT_BASE"
  info ""

  for repoPath in `find "$GERRIT_GIT_BASE" -type d -name '*.git'`; do
    pushd ./ > /dev/null 2>&1
      cd "$repoPath"
      local replicated
      local denyNonFastForward
      replicated=$(git config core.replicated)
      denyNonFastForward=$(git config receive.denyNonFastForwards)
      if [[ "$replicated" == "true" && "$denyNonFastForward" == "true" ]]; then
        REPO_LIST+=("${repoPath}")
      fi
    popd > /dev/null 2>&1
  done

  declare -i size=${#REPO_LIST[@]}

  if [[ "${size}" -gt "0" ]]; then
    info " Found \033[1m${size}\033[0m repositories that must have their configuration updated."

    if [ ! "$NON_INTERACTIVE" == "1" ]; then
      list_repos=$(get_boolean "Would you like to list the affected repositories?" "true")
      if [ "$list_repos" == "true" ]; then
        for entry in ${REPO_LIST[@]}; do
          info " $entry"
        done
      fi
    fi

    if [ ! "$NON_INTERACTIVE" == "1" ]; then
      info ""
      update_repos=$(get_boolean "Update configuration for detected repositories now?" "true")
    else
      if [ "$UPDATE_REPO_CONFIG" == "true" ]; then
        update_repos="true"
      else
        update_repos="false"
      fi
    fi

    if [ "$update_repos" == "true" ]; then

      ## make sure that GitMS is online
      if ! is_gitms_running; then
        info ""
        info " \033[1mERROR:\033[0m GitMS must be running to modify repository configuration"
        info ""
        return -1
      fi

      ## need to get the GitMS Username and password
      local username=$(get_string "GitMS Admin Username")
      get_password "GitMS Admin Password"
      local password="$USER_PASSWORD"

      if [ "$SSL_ENABLED" == "true" ]; then
        local protocol="https://"
        local rest_port="$GITMS_SSL_REST_PORT"
      else
        local protocol="http://"
        local rest_port="$GITMS_REST_PORT"
      fi

      declare -i count=1
      declare -i spin_count=1
      local xml="<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?> \
                  <git-config-properties> \
                    <config> \
                      <section>receive</section> \
                      <property>denyNonFastForwards</property> \
                      <value>false</value> \
                    </config> \
                  </git-config-properties>"

      local line

      for repoPath in ${REPO_LIST[@]}; do
        ## wrap the spinner
        if [ $spin_count -gt 3 ]; then
          spin_count=0
        fi
        line="Updating Config: ${count}/${size}"
        echo -en "${SPINNER[$count]} ${line}\033[0K\r"
        local encodedRepoPath=$(urlencode "$repoPath")
         curl -X PUT --silent \
        -H "Content-Type:application/xml" \
        -u "${username}:${password}" \
        --data "$xml" \
        "${protocol}127.0.0.1:${rest_port}/api/repository/update-config?path=${encodedRepoPath}"
        sleep 0.2
        count+=1
        spin_count+=1
      done

      echo -e "${line}"
      info ""
      bold " Configuration updates complete"
      info ""
    fi
  else
    info ""
    bold " None of the repositories require a configuration change."
    info ""
  fi

  return 0
}

function get_config_from_user() {
  header
  bold " Configuration Information"
  info ""
  find_gitms

  if [ ! "$NON_INTERACTIVE" == "1" ]; then
    while true
    do
      read -e -p " Git Multisite root directory$GITMS_ROOT_PROMPT: " INPUT
      if [ -z "$INPUT" ]; then
        INPUT=$GITMS_ROOT
      fi

      if [[ -d "$INPUT" && -r "$INPUT" ]]; then
        ## The directory exists, but we must ensure it has an application.properties file
        APPLICATION_PROPERTIES="$INPUT"
        APPLICATION_PROPERTIES+="/replicator/properties/application.properties"

        if [ ! -e "$APPLICATION_PROPERTIES" ]; then
          info ""
          info " \033[1mERROR:\033[0m $APPLICATION_PROPERTIES cannot be found"
          info ""
          continue
        fi

        break
      else
        info ""
        info " \033[1mERROR:\033[0m directory does not exist or is not readable"
        info ""
      fi
    done
    
    GITMS_ROOT="$INPUT"

    info ""
    bold " Reading GitMS Configuration..."
    info ""
    fetch_config_from_application_properties
  fi

  ## Copy APPLICATION_PROPERTIES to SCRATCH, so an install aborted part way
  ## will not have modified existing install.
  cp "$APPLICATION_PROPERTIES" "$SCRATCH"
  TMP_APPLICATION_PROPERTIES="$SCRATCH/application.properties"

  if ! is_gitms_running; then
    info " \033[1mWARNING:\033[0m Looks like Git Multisite is not running"
    info ""
  fi

  ## This is either an upgrade for an install which has already used GerritMS, or
  ## it is a clean install. Look at the application.properties to determine if
  ## any necessary values are not present. Only offer to set values which are
  ## not already set.

  if [ -z "$GERRIT_ENABLED" ]; then
    set_property "gerrit.enabled" "true"
  fi

  if [ -z "$GERRIT_ROOT" ]; then

    while true
    do
      get_directory "Gerrit Root Directory" "false"

      ## Check the Gerrit install at this location is good
      check_gerrit_root "$INPUT_DIR"

      if [ ! "$?" == "0" ]; then
        continue
      fi

      break
    done
    GERRIT_ROOT="$INPUT_DIR"
  else
    info " Gerrit Root Directory: $GERRIT_ROOT"

    ## GERRIT_ROOT is either set in non-interactive mode, or from application.properties
    ## It should still be verified, but in this case, a failure exits the install
    ## rather than reprompting for input
    check_gerrit_root "$GERRIT_ROOT"

    if [ "$?" == "1" ]; then
      echo " Exiting install, $GERRIT_ROOT does not point to a valid Gerrit install."
      exit 1;
    fi
  fi

  ## Check if Gerrit is running now that we know the Gerrit root
  if check_gerrit_status -ne 0; then
    ##Gerrit was detected as running, display a warning
    info ""
    info " \033[1mERROR:\033[0m A process has been detected on the Gerrit HTTP port \033[1m$(get_gerrit_port)\033[0m."
    info " Is Gerrit still running? Please make this port available and re-run the installer."
    info ""
    exit 1
  fi

  set_property "gerrit.root" "$GERRIT_ROOT"
  
  if ps aux|grep GerritCodeReview|grep $GERRIT_ROOT |grep -v " grep " > /dev/null 2>&1; then
    info ""
    info " \033[1mWARNING:\033[0m Looks like Gerrit is currently running"
    info ""
  fi

  if [ ! "$NON_INTERACTIVE" == "1" ]; then
    if [ -n "$GERRIT_USERNAME" ]; then
      info ""
      bold " Gerrit Admin Username and Password"
      info ""
      REMOVE_UNAME_AND_PASSWD=$(get_boolean "It is no longer necessary to keep your Gerrit Admin Username or Password. Would you like to remove them?" "true")
      if [ "$REMOVE_UNAME_AND_PASSWD" == "true" ]; then
        remove_property "gerrit.username" "gerrit.password"
      else
        info " Not removing Gerrit Admin Username and Password."

      fi
    fi
  else
    remove_property "gerrit.username" "gerrit.password"
  fi
  info

  if [ -z "$GERRIT_REPO_HOME" ]; then
    get_directory "Gerrit Repository Directory" "false"
    GERRIT_REPO_HOME="$INPUT_DIR"
  else
    info " Gerrit Repository Directory: $GERRIT_REPO_HOME"
  fi
  
  set_property "gerrit.repo.home" "$GERRIT_REPO_HOME"


  if [ -z "$GERRIT_EVENTS_PATH" ]; then
    get_directory "Gerrit Events Directory" "true"
    GERRIT_EVENTS_PATH="$INPUT_DIR"
  else
    info " Gerrit Events Path: $GERRIT_EVENTS_PATH"
  fi

  set_property "gerrit.events.basepath" "$GERRIT_EVENTS_PATH"

  if [ -z "$GERRIT_REPLICATED_EVENTS_SEND" ]; then
    GERRIT_REPLICATED_EVENTS_SEND=$(get_boolean "Will this node send Replicated Events to other Gerrit nodes?" "true")
  else
    info " Gerrit Receive Replicated Events: $GERRIT_REPLICATED_EVENTS_SEND"
  fi

  set_property "gerrit.replicated.events.enabled.send" "$GERRIT_REPLICATED_EVENTS_SEND"


  if [ -z "$GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL" ]; then
    GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL=$(get_boolean "Will this node receive Replicated Events from other Gerrit nodes?" "true")
  else
    info " Gerrit Send Replicated Events: $GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL"
  fi

  set_property "gerrit.replicated.events.enabled.receive.original" "$GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL"

  if [ -z "$GERRIT_REPLICATED_EVENTS_RECEIVE_DISTINCT" ]; then
    GERRIT_REPLICATED_EVENTS_RECEIVE_DISTINCT="false"
  else
    info " Gerrit Receive Replicated Events as distinct: $GERRIT_REPLICATED_EVENTS_RECEIVE_DISTINCT"
  fi
  set_property "gerrit.replicated.events.enabled.receive.distinct" "$GERRIT_REPLICATED_EVENTS_RECEIVE_DISTINCT"

  if [ -z "$GERRIT_REPLICATED_EVENTS_LOCAL_REPUBLISH_DISTINCT" ]; then
    GERRIT_REPLICATED_EVENTS_LOCAL_REPUBLISH_DISTINCT="false"
  else
    info " Gerrit republish local events as distinct: $GERRIT_REPLICATED_EVENTS_LOCAL_REPUBLISH_DISTINCT"
  fi
  set_property "gerrit.replicated.events.enabled.local.republish.distinct" "$GERRIT_REPLICATED_EVENTS_LOCAL_REPUBLISH_DISTINCT"

  if [ -z "$GERRIT_REPLICATED_EVENTS_DISTINCT_PREFIX" ]; then
    GERRIT_REPLICATED_EVENTS_DISTINCT_PREFIX="REPL-"
  else
    info " Gerrit prefix for current node distinct events: $GERRIT_REPLICATED_EVENTS_DISTINCT_PREFIX"
  fi
  set_property "gerrit.replicated.events.distinct.prefix" "$GERRIT_REPLICATED_EVENTS_DISTINCT_PREFIX"

  if [ -z "$GERRIT_REPLICATED_CACHE_ENABLED" ]; then
    GERRIT_REPLICATED_CACHE_ENABLED="true"
  else
    info " Gerrit Replicated Cache enabled: $GERRIT_REPLICATED_CACHE_ENABLED"
  fi
  set_property "gerrit.replicated.cache.enabled" "$GERRIT_REPLICATED_CACHE_ENABLED"

  if [ -z "$GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD" ]; then
    GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD="changes,projects"
  else
    info " Gerrit Replicated Cache exclude reload for: $GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD"
  fi
  set_property "gerrit.replicated.cache.names.not.to.reload" "$GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD"

  if [ -z "$GERRIT_RPGROUP_ID" ]; then
    while true
    do
      GERRIT_RPGROUP_ID=$(get_gerrit_replication_group_id)

      if [ ! -z "$GERRIT_RPGROUP_ID" ]; then
        info ""
        info " Replication Group found with ID: $GERRIT_RPGROUP_ID"
        break
      else
        info ""
        info " \033[1mERROR:\033[0m Could not retrieve Replication Group ID with configuration provided"
        info ""
      fi
    done
  else
    info " Gerrit Replication Group ID: $GERRIT_RPGROUP_ID"
  fi

  set_property "gerrit.rpgroupid" "$GERRIT_RPGROUP_ID"


  if [ -z "$GERRIT_DB_SLAVEMODE_SLEEPTIME" ]; then
    set_property "gerrit.db.slavemode.sleepTime" "0"
  fi

  #prompt for directory to save deleted repos to
  if [ -z "$DELETED_REPO_DIRECTORY" ]; then
    info ""
    bold " Deleted Repositories Directory "
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
      read -e -p " Location for deleted repositories to be moved to : $DELETED_REPO_DEFAULT_DIRECTORY " INPUT

      #If the input is empty then set the directory to the default directory
      if [ -z "$INPUT" ]; then
        INPUT=$DELETED_REPO_DEFAULT_DIRECTORY
        create_directory "$INPUT"
        break
      else
      	INPUT=$(sanitize_path "$INPUT")
        create_directory "$INPUT"
      fi
    done
  else
    info " Deleted Repo Directory: $DELETED_REPO_DIRECTORY"
  fi

  set_property "deleted.repo.directory" $DELETED_REPO_DIRECTORY

  if [ ! "$NON_INTERACTIVE" == "1" ]; then
    info ""
    bold " Helper Scripts"
    info ""
    info " We provide some optional scripts to aid in installation/administration of "
    info " GerritMS. Where should these scripts be installed?"
    info ""

    local default=$(sanitize_path "$GERRIT_ROOT/bin")
    while true
    do
      read -e -p " Helper Script Install Directory [$default]: " INPUT
      if [ -z "$INPUT" ]; then
        INPUT=$default
      fi

      if [[ -d "$INPUT" && -w "$INPUT" ]]; then
        break
      else
        info ""
        info " \033[1mERROR:\033[0m directory does not exist or is not writable"
        info ""
      fi
    done
    SCRIPT_INSTALL_DIR="$INPUT"
  fi
}

function create_directory {
  INPUT_DIR="$1"

  if [[ ! -d "$INPUT_DIR" && ! -e "$INPUT_DIR" ]]; then
    local create_dir=$(get_boolean "The directory [ $INPUT_DIR ] does not exist, do you want to create it?" "true")

    if [ "$create_dir" == "true" ]; then
      mkdir -p "$INPUT_DIR"
      if [ "$?" == "0" ]; then
        DELETED_REPO_DIRECTORY="$INPUT_DIR"
        break
      else
        echo " ERROR: Directory could not be created"
        continue
      fi
    else
      #user does not want to create the directory so prompt them again
      continue
    fi
  elif [ -d "$INPUT_DIR" ]; then
    if [ -w "$INPUT_DIR" ]; then
      DELETED_REPO_DIRECTORY="$INPUT_DIR"
      break
    else
      echo " ERROR: $INPUT_DIR is not writable"
      continue
    fi
  else
    echo " ERROR: $INPUT_DIR does not exist"
    continue
  fi
}

function is_gitms_running() {
  ps aux | grep "com.wandisco.gitms.main.Main" | grep -v grep > /dev/null 2>&1
  return $?
}

## Check if a port is currently being used
# $1 port to check
# returns 0 if port isn't in use
function is_port_available() {
  netstat -an | grep "$1" | grep -i "LISTEN" > /dev/null 2>&1
  if [ $? -eq 0 ]; then
    return 0
  else
    return 1
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
  local gerrit_config_file="$GERRIT_ROOT/etc/gerrit.config"
  export GIT_CONFIG="$gerrit_config_file"
  local gerrit_port=$(git config httpd.listenUrl | cut -d":" -f3 | cut -d"/" -f1)
  unset GIT_CONFIG
  echo "$gerrit_port"
}

function create_backup() {
  header
  bold " Backup Information"
  info ""
  info " Taking a backup of the of GitMS + Gerrit configuration. Where should this"
  info " be saved?"
  info ""

  if [ ! "$NON_INTERACTIVE" == "1" ]; then
    get_directory "Backup Location" "true"
    BACKUP_ROOT="$INPUT_DIR"
  else
    BACKUP_ROOT="$BACKUP_DIR"
  fi

  mkdir -p "$SCRATCH/backup/gerrit"

  if [ "$FIRST_NODE" == "true" ]; then
    ## Fetch some properties from gerrit.config to potentially backup the database
    GIT_CONFIG="$GERRIT_ROOT/etc/gerrit.config"
    export GIT_CONFIG
    local db_name=$(git config database.database)
    unset GIT_CONFIG

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

  rsync -aq --exclude="$gerrit_base_path" "$GERRIT_ROOT/" "${SCRATCH}/backup/gerrit" > /dev/null 2>&1
  cp "$APPLICATION_PROPERTIES" "${SCRATCH}/backup"

  CMD='tar -zcpf "$gerritBackup" -C "$SCRATCH" "backup"'
  if eval $CMD > /dev/null 2> "${tmpFile}"; then
    : # exited ok
  else
    exval=$?
    echo "ERROR: backup command ('$CMD') failed: $exval" 1>&2
    echo "ERROR: backup STDERR was:" 1>&2
    cat "${tmpFile}" 1>&2
    echo "ERROR: end of STDERR." 1>&2
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

  GIT_CONFIG="$tmp_gerrit_config"
  export GIT_CONFIG
  git config receive.timeout 900000
  unset GIT_CONFIG

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
}

function remove_gitms_gerrit_plugin() {
  local plugin_location="$GERRIT_ROOT/plugins/gitms-gerrit-event-plugin.jar"

  if [ -e "$plugin_location" ]; then
    rm -f "$plugin_location"
  fi
}

function install_gerrit_scripts() {
  cp -f "reindex.sh" "$SCRIPT_INSTALL_DIR"
  cp -f "sync_repo.sh" "$SCRIPT_INSTALL_DIR"
}

function write_new_config() {
  header
  bold " Finalizing Install"

  write_gerrit_config
  write_gitms_config
  replace_gerrit_war
  install_gerrit_scripts
  replicated_upgrade

  info ""
  info " GitMS and Gerrit have now been configured."
  info ""
  bold " Next Steps:"
  info ""
  info " * Restart GitMS on this node now to finalize the configuration changes"

  if [[ ! "$FIRST_NODE" == "true" && ! "$REPLICATED_UPGRADE" == "true" ]]; then
    info " * If you have rsync'd this Gerrit installation from a previous node"
    info "   please ensure you have updated the $(sanitize_path "${GERRIT_ROOT}/etc/gerrit.config")"
    info "   file for this node. In particular, the canonicalWebUrl and database settings should"
    info "   be verified to be correct for this node."
  else
    local gerrit_base_path=$(get_gerrit_base_path "$GERRIT_ROOT")
    local syncRepoCmdPath=$(sanitize_path "${SCRIPT_INSTALL_DIR}/sync_repo.sh")

    if [ ! "$REPLICATED_UPGRADE" == "true" ]; then
      info " * rsync $GERRIT_ROOT to all of your GerritMS nodes"
      if [[ "${gerrit_base_path#$GERRIT_ROOT/}" = /* ]]; then
        info " * rsync $gerrit_base_path to all of your GerritMS nodes"
      fi

      info " * On each of your Gerrit nodes, update gerrit.config:"
      info "\t- change the hostname of canonicalURL to the hostname for that node"
      info "\t- ensure that database details are correct"

      info " * Run ${syncRepoCmdPath} on one node to add any existing"
      info "   Gerrit repositories to GitMS. Note that even if this is a new install of"
      info "   Gerrit with no user added repositories, running sync_repo.sh is still"
      info "   required to ensure that All-Projects and All-Users are properly replicated."
    fi

    info " * Run this installer on all of your other Gerrit nodes"
    info " * When all nodes have been installed, you are ready to start the Gerrit services"
    info "   across all nodes."
  fi

  info ""

  if [ "$NON_INTERACTIVE" == "1" ]; then
    echo "Non-interactive install completed"
  fi
}

## Determine the version of a Gerrit war file
function get_gerrit_version() {
  local tmpdir=$(mktemp -d --tmpdir="$SCRATCH")
  unzip -o "$1" -d "$tmpdir" WEB-INF/lib/gerrit-war-version.jar >/dev/null 2>&1
  unzip -p "$tmpdir/WEB-INF/lib/gerrit-war-version.jar" > "$tmpdir/GERRIT-VERSION"
  echo $(cat "$tmpdir/GERRIT-VERSION")
}

## Cleanup installation temp files
function cleanup() {
  if [[ ! -z "$SCRATCH" && -d "$SCRATCH" ]]; then
    rm -rf "$SCRATCH"
  fi
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
  ## These properties will not be set in applicaton.properties
  if [[ ! -z "$GITMS_ROOT" && ! -z "$BACKUP_DIR" && ! -z "$SCRIPT_INSTALL_DIR" && ! -z "$FIRST_NODE" && ! -z "$CURL_ENVVARS_APPROVED" ]]; then

    APPLICATION_PROPERTIES="$GITMS_ROOT"
    APPLICATION_PROPERTIES+="/replicator/properties/application.properties"

    if [ ! -e "$APPLICATION_PROPERTIES" ]; then
      info "ERROR: Non-interactive installation aborted, the file $APPLICATION_PROPERTIES does not exist"
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
    local tmp_gerrit_replicated_events_receive_distinct=$(fetch_property "gerrit.replicated.events.enabled.receive.distinct")
    local tmp_gerrit_replicated_events_local_republish_distinct=$(fetch_property "gerrit.replicated.events.enabled.local.republish.distinct")
    local tmp_gerrit_replicated_events_distinct_prefix=$(fetch_property "gerrit.replicated.events.distinct.prefix")
    local tmp_gerrit_replicated_cache_enabled=$(fetch_property "gerrit.replicated.cache.enabled")
    local tmp_gerrit_replicated_cache_names_not_to_reload=$(fetch_property "gerrit.replicated.cache.names.not.to.reload")

    ## Override env variables where the property already exists
    if [ ! -z "$tmp_gerrit_root" ]; then
      GERRIT_ROOT="$tmp_gerrit_root"
    fi

    if [ ! -z "$tmp_gerrit_rpgroup_id" ]; then
      GERRIT_RPGROUP_ID="$tmp_gerrit_rpgroup_id"
    fi

    if [ ! -z "$tmp_gerrit_repo_home" ]; then
      GERRIT_REPO_HOME="$tmp_gerrit_repo_home"
    fi

    if [ ! -z "$tmp_deleted_repo_directory" ]; then
      DELETED_REPO_DIRECTORY="$tmp_deleted_repo_directory"
    fi

    if [ ! -z "$tmp_gerrit_events_path" ]; then
      GERRIT_EVENTS_PATH="$tmp_gerrit_events_path"
    fi

    if [ ! -z "$tmp_gerrit_replicated_events_send" ]; then
      GERRIT_REPLICATED_EVENTS_SEND="$tmp_gerrit_replicated_events_send"
    fi

    if [ ! -z "$tmp_gerrit_replicated_events_receive_original" ]; then
      GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL="$tmp_gerrit_replicated_events_receive_original"
    fi

    if [ ! -z "$tmp_gerrit_replicated_events_receive_distinct" ]; then
      GERRIT_REPLICATED_EVENTS_RECEIVE_DISTINCT="$tmp_gerrit_replicated_events_receive_distinct"
    fi

    if [ ! -z "$tmp_gerrit_replicated_events_local_republish_distinct" ]; then
      GERRIT_REPLICATED_EVENTS_LOCAL_REPUBLISH_DISTINCT="$tmp_gerrit_replicated_events_local_republish_distinct"
    fi

    if [ ! -z "$tmp_gerrit_replicated_events_distinct_prefix" ]; then
      GERRIT_REPLICATED_EVENTS_DISTINCT_PREFIX="$tmp_gerrit_replicated_events_distinct_prefix"
    fi

    if [ ! -z "$tmp_gerrit_replicated_cache_enabled" ]; then
      GERRIT_REPLICATED_CACHE_ENABLED="$tmp_gerrit_replicated_cache_enabled"
    fi

    if [ ! -z "$tmp_gerrit_replicated_cache_names_not_to_reload" ]; then
      GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD="$tmp_gerrit_replicated_cache_names_not_to_reload"
    fi

    ## Check that all variables are now set to something
    if [[ ! -z "$GERRIT_ROOT"
      && ! -z "$GERRIT_RPGROUP_ID" && ! -z "$GERRIT_REPO_HOME" && ! -z "$GERRIT_EVENTS_PATH" && ! -z "$DELETED_REPO_DIRECTORY"
      && ! -z "$GERRIT_REPLICATED_EVENTS_SEND" && ! -z "$GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL"
      && ! -z "$GERRIT_REPLICATED_EVENTS_RECEIVE_DISTINCT" && ! -z "$GERRIT_REPLICATED_EVENTS_LOCAL_REPUBLISH_DISTINCT"
      && ! -z "$GERRIT_REPLICATED_EVENTS_DISTINCT_PREFIX" && ! -z "$GERRIT_REPLICATED_CACHE_ENABLED"
      && ! -z "$GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD" ]]; then

      ## On an upgrade, some extra variables must be set. If they are not, non-interactive
      ## mode will not be set
      if [ ! -z "$UPGRADE" ]; then
        if [[ -z "$RUN_GERRIT_INIT" || -z "$UPDATE_REPO_CONFIG" || -z "$REMOVE_PLUGIN" ]]; then
          ## not non-interactive, need to set upgrade variables
          NON_INTERACTIVE=0
          return
        fi
      fi

      ## GERRIT_ROOT must exist as well
      if [ ! -d "$GERRIT_ROOT" ]; then
        info "ERROR: Non-interactive installation aborted, the GERRIT_ROOT at $GERRIT_ROOT does not exist"
        exit 1
      fi

      ## DELETED_REPO_DIRECTORY must exist, if it does not attempt to create it
      if [ ! -d "$DELETED_REPO_DIRECTORY" ]; then
        mkdir -p "$DELETED_REPO_DIRECTORY"
        if [ "$?" != "0" ]; then
          info "ERROR: Non-interactive installation aborted, the DELETED_REPO_DIRECTORY at $DELETED_REPO_DIRECTORY does not exist and can not be created"
          exit 1
        fi
      fi
      
      ## CURL_ENVVARS_APPROVED must be either true or false
      if [ ! "$CURL_ENVVARS_APPROVED" == "true" ] && [ ! "$CURL_ENVVARS_APPROVED" == "false" ]; then
        info "ERROR: Non-interactive installation aborted, the CURL_ENVVARS_APPROVED must be either \"true\" or \"false\". Currently is \"$CURL_ENVVARS_APPROVED\""
        exit 1
      fi

      NON_INTERACTIVE=1
    fi
  fi
}

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

check_executables
create_scratch_dir
NON_INTERACTIVE=0
WD_GERRIT_VERSION=$(get_gerrit_version "release.war")
NEW_GERRIT_VERSION=$(echo $WD_GERRIT_VERSION | cut -f1 -d '-')
GERRIT_RELEASE_NOTES="https://gerrit-documentation.storage.googleapis.com/ReleaseNotes/ReleaseNotes-2.13.html"
GERRITMS_INSTALL_DOC="http://docs.wandisco.com/gerrit/1.9/#doc_gerritinstall"

## Versions of Gerrit that we allow the user to upgrade from. Generally a user is not allowed to skip a major
## version, but can skip minor versions. This is not a hard and fast rule however, as the reality of when an
## upgrade can be safely skipped is down to Gerrit upgrade behaviour. This should have all the release versions
## of the previous major version number, and any release versions of the current major version number.
PREVIOUS_ALLOWED_RP_GERRIT_VERSIONS=("v2.11.7-RP-1.7.1.4" "v2.11.9-RP-1.7.2.1" "v2.11.9-RP-1.7.2.2" "v2.13.9-RP-1.9.1.2" "v2.13.9-RP-1.9.2.2" )
REPLICATED_UPGRADE="false"
SPINNER=("|" "/" "-" "\\")

check_for_non_interactive_mode

if [ "$NON_INTERACTIVE" == "1" ]; then
  echo "Starting non-interactive install of GerritMS..."
  echo ""
  echo "Using settings: "
  echo "GITMS_ROOT: $GITMS_ROOT"
  echo "GERRIT_ROOT: $GERRIT_ROOT"
  echo "GERRIT_RPGROUP_ID: $GERRIT_RPGROUP_ID"
  echo "GERRIT_REPO_HOME: $GERRIT_REPO_HOME"
  echo "DELETED_REPO_DIRECTORY: $DELETED_REPO_DIRECTORY"
  echo "GERRIT_EVENTS_PATH: $GERRIT_EVENTS_PATH"
  echo "GERRIT_REPLICATED_EVENTS_SEND: $GERRIT_REPLICATED_EVENTS_SEND"
  echo "GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL: $GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL"
  echo "GERRIT_REPLICATED_EVENTS_RECEIVE_DISTINCT: $GERRIT_REPLICATED_EVENTS_RECEIVE_DISTINCT"
  echo "GERRIT_REPLICATED_EVENTS_LOCAL_REPUBLISH_DISTINCT: $GERRIT_REPLICATED_EVENTS_LOCAL_REPUBLISH_DISTINCT"
  echo "GERRIT_REPLICATED_EVENTS_DISTINCT_PREFIX: $GERRIT_REPLICATED_EVENTS_DISTINCT_PREFIX"
  echo "GERRIT_REPLICATED_CACHE_ENABLED: $GERRIT_REPLICATED_CACHE_ENABLED"
  echo "GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD: $GERRIT_REPLICATED_CACHE_NAMES_NOT_TO_RELOAD"
  echo "BACKUP_DIR: $BACKUP_DIR"
  echo "SCRIPT_INSTALL_DIR: $SCRIPT_INSTALL_DIR"
  echo "FIRST_NODE: $FIRST_NODE"
  echo "UPDATE_REPO_CONFIG: $UPDATE_REPO_CONFIG"
  echo "RUN_GERRIT_INIT: $RUN_GERRIT_INIT"
  echo "REMOVE_PLUGIN: $REMOVE_PLUGIN"
  echo "CURL_ENVVARS_APPROVED: $CURL_ENVVARS_APPROVED"
fi

prereqs
check_environment_variables_that_affect_curl
check_user
get_config_from_user
create_backup
write_new_config
cleanup
