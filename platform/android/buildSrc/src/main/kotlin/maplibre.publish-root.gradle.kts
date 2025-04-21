//extra["signing.keyId"] = "65279219"
//extra["signing.password"] = "StylEbkInDycue2_"
extra["signing.keyId"] = System.getenv("SIGNING_KEY_ID")
extra["signing.password"] = System.getenv("SIGNING_PASSWORD")
extra["signing.secretKeyRingFile"] = "/Users/macbook/.gnupg/secring.gpg"
extra["ossrhUsername"] = System.getenv("OSSRH_USERNAME")
extra["ossrhPassword"] = System.getenv("OSSRH_PASSWORD")
extra["sonatypeStagingProfileId"] = System.getenv("SONATYPE_STAGING_PROFILE_ID")
//7E5538C4FFDF39F7
//gpg --armor --export 7E5538C4FFDF39F7 > mypublickey.asc
//gpg --armor --export-secret-keys 7E5538C4FFDF39F7 > mysecretkey.asc