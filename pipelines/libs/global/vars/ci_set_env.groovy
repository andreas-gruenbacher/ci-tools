@NonCPS
def ldadd(String project, String deps, String INSTALL_PATH, Boolean allow_os_package, Map cienvinfo)
{
    def varmap = [:]
    def found = false

    def install_path_rc = sh(script: "test -d ${INSTALL_PATH}", returnStatus: true)
    if (install_path_rc == 0) {
	def buildinfo = sh(script: "if [ -f ${INSTALL_PATH}/.build-info ]; then cat ${INSTALL_PATH}/.build-info; else echo 'not available'; fi", returnStdout: true).trim()
	println("${project} build info: ${buildinfo}")

	def searchpaths = ['lib', 'lib64', 'lib32', 'share']
	for (spath in searchpaths) {
	    def ldp = "${INSTALL_PATH}/${spath}/"
	    def pcp = "${ldp}pkgconfig"
	    // pkg-config newer versions support --env-only
	    // if the env PKG_CONFIG_PATH does not exists, pkg-config
	    // will revert to default search paths and would return
	    // OS installed versions of a package. We need to be careful
	    // to pass only PATHS that do exists.
	    def pcp_path_rc = sh(script: "test -d ${pcp}", returnStatus: true)
	    if (pcp_path_rc == 0) {
		def pkgcfg_rc = sh(script: "PKG_CONFIG_PATH=${pcp} pkg-config --modversion ${deps}", returnStatus: true)
		if (pkgcfg_rc == 0) {
		    found = true
		    if (cienvinfo['EXTERNAL_CONFIG_PATH'] == '') {
			varmap['EXTERNAL_CONFIG_PATH'] = "${pcp}"
		    } else {
			varmap['EXTERNAL_CONFIG_PATH'] = cienvinfo['EXTERNAL_CONFIG_PATH'] + ":${pcp}"
		    }
		    if (cienvinfo['EXTERNAL_LD_LIBRARY_PATH'] == '') {
			varmap['EXTERNAL_LD_LIBRARY_PATH'] = "${ldp}"
		    } else {
			varmap['EXTERNAL_LD_LIBRARY_PATH'] = cienvinfo['EXTERNAL_LD_LIBRARY_PATH'] + ":${ldp}"
		    }
		    break
		}
	    }
	}
    }

    if (found == false) {
	if (allow_os_package == false) {
	    println("pkg-config ${deps} for ${project} not found!")
	    // fail build?
	} else {
	    println("Using ${deps} for ${project} from the OS (if available)")
	    // do pkg-config for OS? and fail if not found?
	}
    } else {
	println("pkg-config ${deps} for ${project} found!")
    }

    return varmap
}

@NonCPS
def call(Map localinfo, String stageName, String agentName)
{
    def cienv = [:]

    // Disable 'make check' if we are bootstrapping
    if (localinfo['bootstrap'] == 1) {
	cienv['CHECKS'] = 'nochecks'
    }

    cienv['build'] = ''
    if (stageName.endsWith('covscan')) {
	cienv['build'] = 'coverity'
    }
    if (stageName.endsWith('buildrpms')) {
	cienv['build'] = 'rpm'
    }
    if (stageName.endsWith('crosscompile')) {
	cienv['build'] = 'crosscompile'
    }

    if (!localinfo.containsKey('compiler')) {
	cienv['compiler'] = 'gcc'
	cienv['CC'] = cienv['compiler']
    } else {
	cienv['CC'] = localinfo['compiler']
    }

    if (!localinfo.containsKey('MAKE')) {
	cienv['MAKE'] = 'make'
    } else {
	// this is only necessary to simply paralleloutput check
	cienv['MAKE'] = localinfo['MAKE']
    }

    if (localinfo.containsKey('depbuildname')) {
	cienv["${localinfo['depbuildname']}ver"] = localinfo['depbuildversion']
	cienv['extraver'] = localinfo['depbuildname']+'-'+localinfo['depbuildversion']
    } else {
	cienv['extraver'] = ''
    }

    // def path = sh(script: "echo \$PATH", returnStdout: true).trim()
    def path = ["bash", "-c", "echo \$PATH"].execute()
    path.waitFor()
    cienv['PATH'] = "/opt/coverity/bin:" + path.text.trim()
    // def home = sh(script: "echo \$HOME", returnStdout: true).trim()
    // cienv['PATH'] = "/opt/coverity/bin:${path}:${home}/ci-tools"

    def numcpu = sh(script: "nproc", returnStdout: true).trim()

    cienv['PARALLELMAKE'] = "-j ${numcpu}"

    def paralleloutput = sh(script: """
				    rm -f Makefile.stub
				    echo "all:" > Makefile.stub
				    PARALLELOUTPUT=""
				    if ${cienv['MAKE']} -f Makefile.stub ${cienv['PARALLELMAKE']} -O >/dev/null 2>&1; then
					PARALLELOUTPUT="-O"
				    fi
				    if ${cienv['MAKE']} -f Makefile.stub ${cienv['PARALLELMAKE']} -Orecurse >/dev/null 2>&1; then
					PARALLELOUTPUT="-Orecurse"
				    fi
				    rm -f Makefile.stub
				    echo \$PARALLELOUTPUT
				    """, returnStdout: true).trim()

    cienv['PARALLELMAKE'] = "-j ${numcpu} ${paralleloutput}"

    // pacemaker version handling
    // Latest Pacemaker release branch
    cienv['PACEMAKER_RELEASE'] = '2.1'

    if (!cienv.containsKey('pacemakerver')) {
	if (localinfo['target'] == 'main') {
	    cienv['pacemakerver'] = 'main'
	} else {
	    cienv['pacemakerver'] = cienv['PACEMAKER_RELEASE']
	}
    }

    // build / test matrix

    // rpm builds should use standard packages
    if (cienv['build'] != 'rpm') {
	// set all defaults to build against main branches
	// apply stable overrides below
	def LIBQB_INSTALL_PATH = '/srv/libqb/origin/main/'
	def KRONOSNET_INSTALL_PATH = '/srv/kronosnet/origin/main/'
	def COROSYNC_INSTALL_PATH = '/srv/corosync/origin/main/'
	def COROSYNC_QDEVICE_INSTALL_PATH = '/srv/corosync-qdevice/origin/main/'
	def FENCE_AGENTS_INSTALL_PATH = '/srv/fence-agents/origin/main/'
	def RESOURCE_AGENTS_INSTALL_PATH = '/srv/resource-agents/origin/main/'
	def PACEMAKER_INSTALL_PATH = "/srv/pacemaker/origin/" + cienv['pacemakerver'] + "/"
	def BOOTH_INSTALL_PATH = "/srv/booth/origin/main-pacemaker-" + cienv['pacemakerver'] + "/"
	def SBD_INSTALL_PATH = "/srv/sbd/origin/main-pacemaker-" + cienv['pacemakerver'] + "/"

	if ((localinfo['target'] != 'main') ||
	    (cienv['pacemakerver'] != 'main')) {
	    KRONOSNET_INSTALL_PATH = '/srv/kronosnet/origin/stable1-proposed/'
	    COROSYNC_INSTALL_PATH = '/srv/corosync/origin/camelback/'
	}

	// corosync supports both kronosnet stable and main
	// we need to test build both
	if ((localinfo['project'] == 'corosync') &&
	    (cienv.containsKey('kronosnetver'))) {
	    KRONOSNET_INSTALL_PATH = "/srv/kronosnet/origin/" + cienv['kronosnetver'] + "/"
	}

	// generate ld library path and pkgconfig path
	cienv['EXTERNAL_LD_LIBRARY_PATH'] = ''
	cienv['EXTERNAL_CONFIG_PATH']= ''
	cienv += ldadd('libqb', 'libqb', LIBQB_INSTALL_PATH, true, cienv)
	cienv += ldadd('kronosnet', 'libknet', KRONOSNET_INSTALL_PATH, false, cienv)
	cienv += ldadd('corosync', 'corosync', COROSYNC_INSTALL_PATH, false, cienv)
	cienv += ldadd('corosync-qdevice', 'corosync-qdevice', COROSYNC_QDEVICE_INSTALL_PATH, false, cienv)
	cienv += ldadd('fence-agents', 'fence-agents', FENCE_AGENTS_INSTALL_PATH, false, cienv)
	cienv += ldadd('resource-agents', 'resource-agents', RESOURCE_AGENTS_INSTALL_PATH, false, cienv)
	cienv += ldadd('pacemaker', 'pacemaker', PACEMAKER_INSTALL_PATH, false, cienv)
	cienv += ldadd('booth', 'booth', BOOTH_INSTALL_PATH, false, cienv)
	cienv += ldadd('sbd', 'sbd', SBD_INSTALL_PATH, false, cienv)
    } else {
	// same logic as above, for rpm builds
	cienv['LIBQB_REPO'] = "https://ci.kronosnet.org/builds/libqb-main-" + agentName + ".repo"
	cienv['LIBQB_REPO_PATH'] = "https://ci.kronosnet.org/builds/libqb/" + agentName + "/main/latest/"
	cienv['KRONOSNET_REPO'] = "https://ci.kronosnet.org/builds/kronosnet-main-" + agentName + ".repo"
	cienv['KRONOSNET_REPO_PATH'] = "https://ci.kronosnet.org/builds/kronosnet/" + agentName + "/main/latest/"
	cienv['COROSYNC_REPO'] = "https://ci.kronosnet.org/builds/corosync-main-" + agentName + ".repo"
	cienv['COROSYNC_REPO_PATH'] = "https://ci.kronosnet.org/builds/corosync/" + agentName + "/main/latest/"
	cienv['COROSYNC_QDEVICE_REPO'] = "https://ci.kronosnet.org/builds/corosync-qdevice-main-" + agentName + ".repo"
	cienv['COROSYNC_QDEVICE_REPO_PATH'] = "https://ci.kronosnet.org/builds/corosync-qdevice/" + agentName + "/main/latest/"
	cienv['FENCE_AGENTS_REPO'] = "https://ci.kronosnet.org/builds/fence-agents-main-" + agentName + ".repo"
	cienv['FENCE_AGENTS_REPO_PATH'] = "https://ci.kronosnet.org/builds/fence-agents/" + agentName + "/main/latest/"
	cienv['RESOURCE_AGENTS_REPO'] = "https://ci.kronosnet.org/builds/resource-agents-main-" + agentName + ".repo"
	cienv['RESOURCE_AGENTS_REPO_PATH'] = "https://ci.kronosnet.org/builds/resource-agents/" + agentName + "/main/latest/"
	cienv['PACEMAKER_REPO'] = "https://ci.kronosnet.org/builds/pacemaker-" + cienv['pacemakerver'] + "-" + agentName + ".repo"
	cienv['PACEMAKER_REPO_PATH'] = "https://ci.kronosnet.org/builds/pacemaker/" + agentName + "/" + cienv['pacemakerver'] + "/latest/"
	cienv['BOOTH_REPO'] = "https://ci.kronosnet.org/builds/booth-main-pacemaker-" + cienv['pacemakerver'] + "-" + agentName + ".repo"
	cienv['BOOTH_REPO_PATH'] = "https://ci.kronosnet.org/builds/booth/" + agentName + "/main-pacemaker-" + cienv['pacemakerver'] + "/latest/"
	cienv['SBD_REPO'] = "https://ci.kronosnet.org/builds/sbd-main-pacemaker-" + cienv['pacemakerver'] + "-" + agentName + ".repo"
	cienv['SBD_REPO_PATH'] = "https://ci.kronosnet.org/builds/sbd/" + agentName + "/main-pacemaker-" + cienv['pacemakerver'] + "/latest/"
	cienv['DLM_REPO'] = "https://ci.kronosnet.org/builds/dlm-main-" + agentName + ".repo"
	cienv['DLM_REPO_PATH'] = "https://ci.kronosnet.org/builds/dlm/" + agentName + "/main/latest/"
	cienv['GFS2UTILS_REPO'] = "https://ci.kronosnet.org/builds/gfs2-utils-main-" + agentName + ".repo"
	cienv['GFS2UTILS_REPO_PATH'] = "https://ci.kronosnet.org/builds/gfs2-utils/" + agentName + "/main/latest/"

	if ((localinfo['target'] != 'main') ||
	    (cienv['pacemakerver'] != 'main')) {
	    cienv['KRONOSNET_REPO'] = "https://ci.kronosnet.org/builds/kronosnet-stable1-proposed-" + agentName + ".repo"
	    cienv['KRONOSNET_REPO_PATH'] = "https://ci.kronosnet.org/builds/kronosnet/" + agentName + "/stable1-proposed/latest/"
	    cienv['COROSYNC_REPO'] = "https://ci.kronosnet.org/builds/corosync-camelback-" + agentName + ".repo"
	    cienv['COROSYNC_REPO_PATH'] = "https://ci.kronosnet.org/builds/corosync/" + agentName + "/camelback/latest/"
	}

	// corosync supports both kronosnet stable and main
	// we need to test build both
	if ((localinfo['project'] == 'corosync') &&
	    (cienv.containsKey('kronosnetver'))) {
	    cienv['KRONOSNET_REPO'] = "https://ci.kronosnet.org/builds/kronosnet-" + cienv['kronosnetver'] + "-" + agentName + ".repo"
	    cienv['KRONOSNET_REPO_PATH'] = "https://ci.kronosnet.org/builds/kronosnet/" + agentName + "/" + cienv['kronosnetver'] + "/latest/"
	}
    }

    // Global things
    cienv['PIPELINE_VER'] = '1'

    return cienv
}
