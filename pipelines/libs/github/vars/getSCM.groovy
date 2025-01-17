// Checkout github
// When called on the built-in node, we take the sources that have been
// checked out by Jenkins and tar them up in a web-accessible place.
// Worker nodes then just download this tarball and unpack it,
// avoiding multiple git calls.
def call(Map info)
{
    def tarfile = "sources-${env.BUILD_TAG}.tar.gz"
    println("tarfile = ${tarfile}, node=${env.NODE_NAME}")

    if (env.NODE_NAME == 'built-in') {
	shNoTrace("tar --exclude=${tarfile} -czf /var/www/ci.kronosnet.org/buildsources/${tarfile} .",
		  "tar --exclude=${tarfile} -czf <redacted-web-dir>/${tarfile} .")
	info['tarfile'] = tarfile
    } else {
	dir (info['project']) {
	    // Random delay to stop hitting the server too hard
	    sleep(new Random().nextInt(15))
	    sh "wget -4 -nv https://ci.kronosnet.org/buildsources/${tarfile}"
	    sh "tar --no-same-owner -xzf ${tarfile}"
	    sh "rm ${tarfile}"
	}
    }
}
