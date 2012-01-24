package com.jsuereth
package pgp
package sbtplugin


import sbt._
import Keys._
import sbt.Project.Initialize
import complete.Parser
import complete.DefaultParsers._

/**
 * Plugin for doing PGP security tasks.  Signing, verifying, etc.
 */
object PgpPlugin extends Plugin {
  
  // PGP related tasks/settings
  val pgpSigner = TaskKey[PgpSigner]("pgp-signer", "The helper class to run GPG commands.")  
  val pgpVerifier = TaskKey[PgpVerifier]("pgp-verifier", "The helper class to verify public keys from a public key ring.")  
  val pgpSecretRing = SettingKey[File]("pgp-secret-ring", "The location of the secret key ring.  Only needed if using bouncy castle.")
  val pgpPublicRing = SettingKey[File]("pgp-public-ring", "The location of the secret key ring.  Only needed if using bouncy castle.")
  val pgpPassphrase = SettingKey[Option[Array[Char]]]("pgp-passphrase", "The passphrase associated with the secret used to sign artifacts.")
  val pgpGenKey = InputKey[Unit]("pgp-gen-key", "Creates a new PGP key using bouncy castle.   Must provide <name> <email>.  The passphrase setting must be set for this to work.")
  val pgpSigningKey = SettingKey[Option[Long]]("pgp-signing-key", "The key used to sign artifacts in this project.  Must be the full key id (not just lower 32 bits).")
  
  
  // GPG Related Options
  val gpgCommand = SettingKey[String]("gpg-command", "The path of the GPG command to run")
  val useGpg = SettingKey[Boolean]("use-gpg", "If this is set to true, the GPG command line will be used.")
  val useGpgAgent = SettingKey[Boolean]("use-gpg-agent", "If this is set to true, the GPG command line will expect a GPG agent for the password.")
  
  // Checking PGP Signatures options
  val signaturesModule = TaskKey[GetSignaturesModule]("signatures-module")
  val updatePgpSignatures = TaskKey[UpdateReport]("update-pgp-signatures", "Resolves and optionally retrieves signatures for artifacts, transitively.")
  val checkPgpSignatures = TaskKey[SignatureCheckReport]("check-pgp-signatures", "Checks the signatures of artifacts to see if they are trusted.")

  // TODO - home dir, use-agent, 
  // TODO - --batch and pasphrase and read encrypted passphrase...
  // TODO --local-user
  // TODO --detach-sign
  // TODO --armor
  // TODO --no-tty
  // TODO  Signature extension
  
  override val settings = Seq(
    skip in pgpSigner := false,
    useGpg := false,
    useGpgAgent := false,
    gpgCommand := (if(isWindows) "gpg.exe" else "gpg"),
    pgpPassphrase := None,
    pgpPublicRing := file(System.getProperty("user.home")) / ".gnupg" / "pubring.gpg",
    pgpSecretRing := file(System.getProperty("user.home")) / ".gnupg" / "secring.gpg",
    pgpSigningKey := None,
    // If the user isn't using GPG, we'll use a bouncy-castle ring.
    pgpPublicRing <<= pgpPublicRing apply {
      case f if f.exists => f
      case _ => file(System.getProperty("user.home")) / ".sbt" / "gpg" / "pubring.asc"
    },
    pgpSecretRing <<= pgpSecretRing apply {
      case f if f.exists => f
      case _ => file(System.getProperty("user.home")) / ".sbt" / "gpg" / "secring.asc"
    },
    pgpSigner <<= (pgpSecretRing, pgpSigningKey, pgpPassphrase, gpgCommand, useGpg, useGpgAgent) map { (secring, optKey, optPass, command, b, agent) =>
      if(b) new CommandLineGpgSigner(command, agent, optKey)
      else {
        val p = optPass getOrElse readPassphrase()
        new BouncyCastlePgpSigner(secring, p, optKey)
      }
    },
    pgpVerifier <<= (pgpPublicRing, gpgCommand, useGpg) map { (pubring, command, b) =>
      if(b) new CommandLineGpgVerifier(command)
      else new BouncyCastlePgpVerifier(pubring)
    },
    packagedArtifacts <<= (packagedArtifacts, pgpSigner, skip in pgpSigner, streams) map {
      (artifacts, r, skipZ, s) =>
        if (!skipZ) {
          artifacts flatMap {
            case (art, file) =>
              Seq(art                                                -> file, 
                  art.copy(extension = art.extension + gpgExtension) -> r.sign(file, new File(file.getAbsolutePath + gpgExtension), s))
          }
        } else artifacts
    },
    pgpGenKey <<= InputTask(keyGenParser)(keyGenTask),
    // TODO - This is checking SBT and its plugins signatures..., maybe we can have this be a separate config or something.
    /*signaturesModule in updateClassifiers <<= (projectID, sbtDependency, loadedBuild, thisProjectRef) map { ( pid, sbtDep, lb, ref) =>
			val pluginIDs: Seq[ModuleID] = lb.units(ref.build).unit.plugins.fullClasspath.flatMap(_ get moduleID.key)
			GetSignaturesModule(pid, sbtDep +: pluginIDs, Configurations.Default :: Nil)
		},*/
    signaturesModule in updatePgpSignatures <<= (projectID, libraryDependencies) map { ( pid, deps) =>
      GetSignaturesModule(pid, deps, Configurations.Default :: Nil)
    },
    updatePgpSignatures <<= (ivySbt, 
                          signaturesModule in updatePgpSignatures, 
                          updateConfiguration, 
                          ivyScala, 
                          target in LocalRootProject, 
                          appConfiguration, 
                          streams) map { (is, mod, c, ivyScala, out, app, s) =>
      PgpSignatureCheck.resolveSignatures(is, GetSignaturesConfiguration(mod, c, ivyScala), s.log)
    },
    checkPgpSignatures <<= (updatePgpSignatures, pgpVerifier, streams) map PgpSignatureCheck.checkSignaturesTask
  )
  
  def usePgpKeyHex(id: String) =
    pgpSigningKey := Some(java.lang.Long.parseLong(id, 16))

  private[this] def keyGenParser: State => Parser[(String,String)] = {
      (state: State) =>
        val Email: Parser[String] =  (NotSpace ~ '@' ~ NotSpace ~ '.' ~ NotSpace) map { 
          case name ~ at ~ address ~ dot ~ subdomain => 
            new StringBuilder(name).append(at).append(address).append(dot).append(subdomain).toString
        }
        val name: Parser[String] = (any*) map (_ mkString "")
        (Space ~> token(Email) ~ (Space ~> token(name)))
  }
  private[this] def keyGenTask = { (parsed: TaskKey[(String,String)]) => 
    (parsed, pgpPublicRing, pgpSecretRing, pgpSigner, streams) map { (input, pub, sec, runner, s) =>
      if(pub.exists)  error("Public key ring (" + pub.getAbsolutePath + ") already exists!")
      if(sec.exists)  error("Secret key ring (" + sec.getAbsolutePath + ") already exists!")
      val (email, name) = input
      val identity = "%s <%s>".format(name, email)
      runner.generateKey(pub, sec, identity, s)
    }
  }
}
