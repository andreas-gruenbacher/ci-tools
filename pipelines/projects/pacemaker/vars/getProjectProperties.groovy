// Return options for this build.
// Return a map of key=value pairs that will
// be passed into the environment on the building node.
//
// localinfo contains all of the build information
// agentName is the node we are building on,
def call(Map localinfo, String agentName)
{
    def props = [:]

    props['MAKEOPTS'] = ''
    props['MAKEINSTALLOPTS'] = ''
    props['TOPTS'] = ''
    props['EXTRACHECKS'] = ''
    props['EXTERNAL_LD_LIBRARY_PATH'] = ''
    props['SPECVERSION'] = env.BUILD_NUMBER
    props['RPMDEPS'] = 'corosynclib-devel'
    props['MAKERPMOPTS'] = 'RPMDEST=subtree'

    props['DISTROCONFOPTS'] = ''

    if (agentName.startsWith("rhel8")) {
	props['DISTROCONFOPTS'] += ' --with-cibsecrets=yes --with-concurrent-fencing-default=true --enable-legacy-links=yes'
    }

    return props
}
