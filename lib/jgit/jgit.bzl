load("//tools/bzl:maven_jar.bzl", "MAVEN_CENTRAL", "MAVEN_LOCAL", "WANDISCO_ASSETS", "maven_jar")

_JGIT_VANILLA_VERS = "5.1.16"
_DOC_VERS = "5.1.15.202012011955-r"  # Set to _JGIT_VANILA_VERS unless using a snapshot

# Defines the WD postfix
_POSTFIX_WD = "-WDv3"

# Defines the version of jgit, even the replicated version of jgit, should be no external use of the vanilla version.
_JGIT_VERS = _JGIT_VANILLA_VERS + _POSTFIX_WD

JGIT_DOC_URL = "https://archive.eclipse.org/jgit/site/" + _DOC_VERS + "/apidocs"

_JGIT_REPO = WANDISCO_ASSETS  # Leave here even so can be set to different maven repos easily.

# set this to use a local version.
# "/home/<user>/projects/jgit"
LOCAL_JGIT_REPO = ""

def jgit_repos():
    if LOCAL_JGIT_REPO:
        native.local_repository(
            name = "jgit",
            path = LOCAL_JGIT_REPO,
        )
        jgit_maven_repos_dev()
    else:
        jgit_maven_repos()

def jgit_maven_repos_dev():
    # Transitive dependencies from JGit's WORKSPACE.
    maven_jar(
        name = "hamcrest-library",
        artifact = "org.hamcrest:hamcrest-library:1.3",
        sha1 = "4785a3c21320980282f9f33d0d1264a69040538f",
    )

    maven_jar(
        name = "jzlib",
        artifact = "com.jcraft:jzlib:1.1.1",
        sha1 = "a1551373315ffc2f96130a0e5704f74e151777ba",
    )

def jgit_maven_repos():
    maven_jar(
        name = "jgit-lib",
        artifact = "org.eclipse.jgit:org.eclipse.jgit:" + _JGIT_VERS,
        repository = _JGIT_REPO,
        sha1 = "462d20dd121d7b782bf85c073f7ae915cd7cec6a",
        src_sha1 = "bc963058dd4c23dddca78be7a76f7554fb105dca",
        unsign = True,
    )
    maven_jar(
        name = "jgit-servlet",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.http.server:" + _JGIT_VERS,
        repository = _JGIT_REPO,
        sha1 = "909717c3d95a579ace355fb41ae5fa5b83929a47",
        unsign = True,
    )
    maven_jar(
        name = "jgit-archive",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.archive:" + _JGIT_VERS,
        repository = _JGIT_REPO,
        sha1 = "a168094b5ed4606ec0e50a7f32c5772b4bb29ce5",
    )
    maven_jar(
        name = "jgit-junit",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.junit:" + _JGIT_VERS,
        repository = _JGIT_REPO,
        sha1 = "09f2ee968b48699d6d1a2735829ea4c7363a07f4",
        unsign = True,
    )

    # Added to support lfs as core plugin from gerrit workspace
    maven_jar(
        name = "jgit-http-apache",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.http.apache:" + _JGIT_VERS,
        sha1 = "d5a9c9b8837961e913df20d198660167ab57baff",
        repository = _JGIT_REPO,
        unsign = True,
        exclude = [
            "about.html",
            "plugin.properties",
        ],
    )

    maven_jar(
        name = "jgit-lfs",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.lfs:" + _JGIT_VERS,
        sha1 = "e04fbb4ffc0f0b8d55b3c0f86bfb2cbeac74a13d",
        repository = _JGIT_REPO,
        unsign = True,
        exclude = [
            "about.html",
            "plugin.properties",
        ],
    )

    maven_jar(
        name = "jgit-lfs-server",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.lfs.server:" + _JGIT_VERS,
        sha1 = "dc632298b5f63c270c5582da7a25638fa3234a6c",
        repository = _JGIT_REPO,
        unsign = True,
        exclude = [
            "about.html",
            "plugin.properties",
        ],
    )

def jgit_dep(name):
    mapping = {
        "@jgit-archive//jar": "@jgit//org.eclipse.jgit.archive:jgit-archive",
        "@jgit-junit//jar": "@jgit//org.eclipse.jgit.junit:junit",
        "@jgit-lib//jar": "@jgit//org.eclipse.jgit:jgit",
        "@jgit-lib//jar:src": "@jgit//org.eclipse.jgit:libjgit-src.jar",
        "@jgit-servlet//jar": "@jgit//org.eclipse.jgit.http.server:jgit-servlet",
        "@jgit-http-apache//jar": "@jgit//org.eclipse.jgit.http.apache:jgit-http-apache",
        "@jgit-lfs//jar": "@jgit//org.eclipse.jgit.lfs:jgit-lfs",
        "@jgit-lfs-server//jar": "@jgit//org.eclipse.jgit.lfs.server:jgit-lfs-server",
    }

    if LOCAL_JGIT_REPO:
        return mapping[name]
    else:
        return name
