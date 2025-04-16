# WANdisco Replicated Fork - GerritMS
For additional WANdisco only internal operational details, see
[README_INTERNAL](./README_INTERNAL.md).

## Local Development Build Setup

### Asset Repository Information
By default the system is setup to pull any WANDisco specific assets, which are using the WD repository header, to use
the public artifact location on 'WANDisco Nexus (nexus.wandisco.com)'.  

This allows this build assets to be available outside of the WANDisco network for any of our public and 
open sourced assets.

This default location is specified in the bazel local.properties file.

For example:
  ```download.WANDISCO = https://nexus.wandisco.com/repository/wd_releases```


To allow this value to be specified for your own organization or local artifactory server ( when testing new assets ) the
value of the WANDISCO repository can be overridden.
Please note you should not change the default value in the local.properties within the gerrit repository - this is the default
value to be used when open sourcing or building outside WANdisco domain. 
Instead, we allow the specification of values for all the gerrit bazel builds, by using a per-user override.  
This can be achieved by placing a new local.properties file in your users gerrit code review directory.  

If you wish to set up this value manually:

1) Go to ~/.gerritcodereview directory.
2) Create a new file called local.properties.
3) Create a new property value to override the repository:  
   ```download.WANDISCO = https://myrepo.com/artifactory/myassets```
4) Save the file



## Building Codebase and Installers

To build the replicated source tree, by default we have added some simplified build wrappers.
The wrappers, perform several build steps which allow building / testing and deploying of all the assets required.

_Makefile wrapper around bazel / bazelisk targets._


To run a full build including all compilation / tests and installers  
```make all```

to run a fast build with only minimal code for release.war  
```make fast-assembly```

to run a fast build but include the installers  
```make fast-assembly installer```

to run all tests  
```make tests```

to run a single test  
```make run-single-test testFilter=CustomGsonDeserializerTest testLocation=//javatests/com/google/gerrit/server:server_tests```

to run a single test but suspend and wait for remote debugging:  
```make run-single-test testFilter=CustomGsonDeserializerTest testLocation=//javatests/com/google/gerrit/server:server_tests testOptionalArgs=--java_debug```

## Additional Tools

- [tools/check_sha.py](https://workspace.wandisco.com/display/GIT/GerritMS+Build+Helper+Scripts)
  Script to automatically update bazel dependencies in the build files.

  Typical usage to update all bazel dependencies for org.eclipse.jgit.\* and com.wandisco.\*
  using their default configured repositories would be:

      $ tools/check_sha.py -vpe

  For full usage options, run:

      $ tools/check_sha.py -h
