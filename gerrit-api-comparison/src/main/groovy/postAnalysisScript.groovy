// Ignore anything not used by External users.
def clientPackage = "com.wandisco.gerrit.gitms.shared.client"
def ignoredPackages = ["com.wandisco.gerrit.gitms.shared.util", "com.wandisco.gerrit.gitms.shared.events"]
def ignoredClasses = ["GitUpdateObjectFactory"]
def ignoredMethods = ["hashCode", "toString", "getLogger", "equals"]
def ignoredClassMethods = ["GitUpdateObjectsFactory": ["buildUpdateRequestWithPackfile"]]

/**
 * Print a log message.
 * @param message THe message to print
 */
static void info(message) {
    println "[INFO] " + message
}

/**
 * Check if the package is ignored or not.
 * @param fullyQualifiedDomainName The name to check.
 * @param ignoredPackages The list of ignored packages
 * @return True if the class should be ignored.
 */
static boolean isPackageIgnored(fullyQualifiedDomainName, ignoredPackages) {
    for (packageName in ignoredPackages) {
        if (fullyQualifiedDomainName.startsWith(packageName)) {
            info "Ignoring " + fullyQualifiedDomainName + " because it is in package " + packageName
            return true
        }
    }
    return false
}

/**
 * Check if it is a class in the client package.
 * @param fullyQualifiedDomainName The name to check
 * @param clientPackage The client package
 * @return True if the class should be ignored.
 */
static boolean isNonLFSClassInClientPackage(fullyQualifiedDomainName, clientPackage) {
    if (fullyQualifiedDomainName.startsWith(clientPackage) && !fullyQualifiedDomainName.contains("LFS")) {
        info "Ignoring " + fullyQualifiedDomainName + " because it is in package " + clientPackage + " and does not have 'LFS' in it's name"
        return true
    }
    return false
}

/**
 * Check if the class should be ignored.
 * @param fullyQualifiedDomainName The name to check
 * @param ignoredClasses List of ignored classes
 * @return True if the class should be ignored.
 */
static boolean isClassIgnored(fullyQualifiedDomainName, ignoredClasses) {
    for (className in ignoredClasses) {
        if (fullyQualifiedDomainName.endsWith(className)) {
            info "Ignoring " + fullyQualifiedDomainName + " because it is in the ignore classes list."
            return true
        }
    }
    return false
}

/**
 * Check if any methods should be ignored.
 * @param jApiClass The class to check
 * @param ignoredClassMethods List of class methods to ignore
 * @param ignoredMethods List of methods to ignore
 */
static void ignoreMethods(jApiClass, ignoredClassMethods, ignoredMethods) {
    def methodIt = jApiClass.getMethods().iterator()

    def methodsToIgnore = ignoredMethods
    def className = jApiClass.getFullyQualifiedName().replaceAll(".*\\.", "")
    def classMethods = ignoredClassMethods[className]
    if (classMethods != null) {
        methodsToIgnore.addAll(classMethods)
    }

    while (methodIt.hasNext()) {
        def method = methodIt.next()
        if (isMethodIgnored(method, methodsToIgnore)) {
            methodIt.remove()
            info "Ignoring " + className + "." + method.getName() + "()"
        }
    }
}

/**
 * Check if a method should be ignored.
 * @param method The method to check
 * @param ignoredMethods List of methods to ignore.
 * @return True if the method should be ignored.
 */
static boolean isMethodIgnored(method, ignoredMethods) {
    for (methodName in ignoredMethods) {
        if (method.getName().equals(methodName)) {
            return true
        }
    }
    return false
}

info "The following packages will be ignored: " + ignoredPackages
info "The following classes will be ignored: " + ignoredClasses
info "Any class in " + clientPackage + " without 'LFS' in the name will be ignored."
info "The following methods will be ignored: " + ignoredMethods
info "The follow class methods will be ignored: " + ignoredClassMethods

def it = jApiClasses.iterator()
while (it.hasNext()) {
    def jApiClass = it.next()
    def fullyQualifiedDomainName = jApiClass.getFullyQualifiedName()

    // Ignore change status, packages and classes.
    if (isPackageIgnored(fullyQualifiedDomainName, ignoredPackages) ||
            isClassIgnored(fullyQualifiedDomainName, ignoredClasses) ||
            isNonLFSClassInClientPackage(fullyQualifiedDomainName, clientPackage)) {
        it.remove()
        continue
    }

    // Ignore methods
    ignoreMethods(jApiClass, ignoredClassMethods, ignoredMethods)
}

return jApiClasses
