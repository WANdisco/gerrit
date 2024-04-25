load("//tools/bzl:maven_jar.bzl", "MAVEN_CENTRAL", "MAVEN_LOCAL", "WANDISCO_ASSETS", "maven_jar")

_JGIT_VANILLA_VERS = "5.1.16"
_DOC_VERS = "5.1.15.202012011955-r"  # Set to _JGIT_VANILA_VERS unless using a snapshot

# Upstream note about doc version mismatch on 2.16 branch:
# TODO: workaround to be removed when merging upstream:
# 5.1.16.202106041830-r has been removed while
# 5.1.15.202012011955-r is available and has the same
# interface and docs

# Defines the WD postfix
_POSTFIX_WD = "-WDv10"

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
        #sha1 = "0930320e65e2e43b0eada074247183a045d57d5b",
        #src_sha1 = "f3ff722e2786e9b7e1c92d210baed5e0b76bf372",
        unsign = True,
    )
    maven_jar(
        name = "jgit-servlet",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.http.server:" + _JGIT_VERS,
        repository = _JGIT_REPO,
        #sha1 = "7228b739c6da84c15ce12f4ce0678a2b14773ed2",
        unsign = True,
    )
    maven_jar(
        name = "jgit-archive",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.archive:" + _JGIT_VERS,
        repository = _JGIT_REPO,
        #sha1 = "6b51ca66d6d10aa334f2138531f3881b7d4e78a8",
    )
    maven_jar(
        name = "jgit-junit",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.junit:" + _JGIT_VERS,
        repository = _JGIT_REPO,
        #sha1 = "f6c89cf7d89e90171d33869a79bac77f1d6576e7",
        unsign = True,
    )

    # Added to support lfs as core plugin from gerrit workspace
    maven_jar(
        name = "jgit-http-apache",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.http.apache:" + _JGIT_VERS,
        #sha1 = "660860cac83a4ed837ccece71f9cb2333592e14f",
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
        #sha1 = "fa47f38f2b0fe49e02a05287630660e592c45521",
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
        #sha1 = "9a72fcbaee0ffd8cf4259fc12617a9f8151afcaa",
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
