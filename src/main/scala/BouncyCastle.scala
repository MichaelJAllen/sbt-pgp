package com.github.jsuereth.gpg

import sbt._
import org.bouncycastle._
import java.io._
import java.io.File
import java.security.{SecureRandom,Security,KeyPairGenerator}
import java.util.Date

import org.bouncycastle.bcpg._
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp._


class PrivateKey(val nested: PGPSecretKey) {

   def isSigningKey = nested.isSigningKey
   def isMasterKey = nested.isMasterKey
   /** Returns the public key associated with this key. */
   def publicKey = PublicKey(nested.getPublicKey)
   // TODO - Add a version of sign that takes an input stream, a name and a 'length' to sign. 
   /** Signs an input stream of bytes and writes it to the output stream. */
   def signFile(file: File, out: OutputStream, pass: Array[Char]): Unit = {
    // TODO - get secret key
    val pgpPrivKey = nested.extractPrivateKey(pass, "BC");        
    val sGen = new PGPSignatureGenerator(nested.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA1, "BC")
    sGen.initSign(PGPSignature.BINARY_DOCUMENT, pgpPrivKey)
    for(name <- this.publicKey.userIDs) {
      val spGen = new PGPSignatureSubpacketGenerator()
      spGen.setSignerUserID(false, name)
      sGen.setHashedSubpackets(spGen.generate())
    }
    val cGen = new PGPCompressedDataGenerator(CompressionAlgorithmTags.ZLIB)
    val bOut = new BCPGOutputStream(cGen open out)
    sGen.generateOnePassVersion(false).encode(bOut)
    val lGen = new PGPLiteralDataGenerator()
    val lOut = lGen.open(bOut, PGPLiteralData.BINARY, file)
    val in = new BufferedInputStream(new FileInputStream(file))
    var ch = in.read()
    while (ch >= 0) {
      lOut.write(ch)
      sGen.update(ch.asInstanceOf[Byte])
      in.read()
    }
    in.close()
    lGen.close()
    sGen.generate().encode(bOut)
    cGen.close()
  }

  def userIDs = new Traversable[String] {
    def foreach[U](f: String => U) = {
      val i = nested.getUserIDs
      while(i.hasNext) f(i.next.toString)
    }
  }
  override lazy val toString = "Private(%x, %s)".format(nested.getKeyID, userIDs.mkString(","))
}

object PrivateKey {
  def apply(nested: PGPSecretKey) = new PrivateKey(nested)
}

class PublicKey(val nested: PGPPublicKey) {
  /** Returns true if a signature is valid for this key. */
  def verifySignature(input: InputStream): Boolean = {
    val in = PGPUtil.getDecoderStream(input)
    val pgpFact = {
      val tmp = new PGPObjectFactory(in)
      val c1 = tmp.nextObject().asInstanceOf[PGPCompressedData]
      new PGPObjectFactory(c1.getDataStream())
    }
    val p1 = pgpFact.nextObject().asInstanceOf[PGPOnePassSignatureList]
    val ops = p1.get(0);
    val p2 = pgpFact.nextObject().asInstanceOf[PGPLiteralData]
    val dIn = p2.getInputStream()
    assert(ops.getKeyID() == nested.getKeyID)
    ops.initVerify(nested, "BC");
    var ch = dIn.read()
    while (ch >= 0) {
      ops.update(ch.asInstanceOf[Byte])
      ch = dIn.read()
    }
    val p3 = pgpFact.nextObject().asInstanceOf[PGPSignatureList]
    ops.verify(p3.get(0))
  }
  def userIDs = new Traversable[String] {
    def foreach[U](f: String => U) = {
      val i = nested.getUserIDs
      while(i.hasNext) f(i.next.toString)
    }
  }
  override lazy val toString = "PublicKey(%x, %s)".format(nested.getKeyID, userIDs.mkString(","))
}
object PublicKey {
  def apply(nested: PGPPublicKey) = new PublicKey(nested)
  implicit def unwrap(key: PublicKey) = key.nested
}

/** A wrapper to simplify working with tyhe Java PGP API. */
class PublicKeyRing(val nested: PGPPublicKeyRing) {
  /** A collection that will traverse all public keys in this key ring. */
  def publicKeys = new Traversable[PublicKey] {
    def foreach[U](f: PublicKey => U): Unit = {
      val it = nested.getPublicKeys
      while(it.hasNext) {
        f(PublicKey(it.next.asInstanceOf[PGPPublicKey]))
      }
    }
  }
  /** A collection that will traverse all keys that can be used to encrypt data. */
  def encryptionKeys = publicKeys.view filter (_.isEncryptionKey)
  def defaultEncryptionKey = encryptionKeys.headOption getOrElse error("No encryption key found.")

  /** Returns true if a signature is valid. */
  def verifySignature(input: InputStream): Boolean = {
    val in = PGPUtil.getDecoderStream(input)
    val pgpFact = {
      val tmp = new PGPObjectFactory(in)
      val c1 = tmp.nextObject().asInstanceOf[PGPCompressedData]
      new PGPObjectFactory(c1.getDataStream())
    }
    val p1 = pgpFact.nextObject().asInstanceOf[PGPOnePassSignatureList]
    val ops = p1.get(0);
    val p2 = pgpFact.nextObject().asInstanceOf[PGPLiteralData]
    val dIn = p2.getInputStream()
    val key = nested.getPublicKey(ops.getKeyID())
    // TODO - Optionally write the file...
    //val out = new FileOutputStream(p2.getFileName());
    ops.initVerify(key, "BC");
    var ch = dIn.read()
    while (ch >= 0) {
      ops.update(ch.asInstanceOf[Byte])
      //out.write(ch)
      ch = dIn.read()
    }
    //out.close()
    val p3 = pgpFact.nextObject().asInstanceOf[PGPSignatureList]
    ops.verify(p3.get(0))
  }
  override lazy val toString = "PublicKeyRing("+publicKeys.mkString(",")+")"
}
object PublicKeyRing {
  implicit def unwrap(ring: PublicKeyRing): PGPPublicKeyRing = ring.nested
  def apply(nested: PGPPublicKeyRing) = new PublicKeyRing(nested)
}
/** A wrapper to simplify working with tyhe Java PGP API. */
class SecretKeyRing(val nested: PGPSecretKeyRing) {

  /** The default public key for this key ring. */
  def getPublicKey = new PublicKey(nested.getPublicKey)
}
object SecretKeyRing {
  implicit def unwrap(ring: SecretKeyRing) = ring.nested
  def apply(ring: PGPSecretKeyRing) = new SecretKeyRing(ring)
}

object BouncyCastle {

  /** This can load your local PGP keyring. */
  def loadPublicKeyRing(file: File) = 
    PublicKeyRing(new PGPPublicKeyRing(PGPUtil.getDecoderStream(new FileInputStream(file))))

 /** Creates a new public/private key pair for PGP encryption using BouncyCastle. */
 def makeKeys(identity: String, passPhrase: Array[Char], dir: File): Unit = {
   Security addProvider new BouncyCastleProvider()
   val kpg = KeyPairGenerator.getInstance("RSA", "BC")
   kpg.initialize(1024)
   val kp = kpg.generateKeyPair()
   if (!dir.exists) { IO.createDirectory(dir) }
   // TODO - Allow naming the output files.
   exportKeyPair(secretOut = new FileOutputStream(dir / "secret.asc"),
                 publicOut = new FileOutputStream(dir / "public.asc"),
                 publicKey = kp.getPublic(),
                 privateKey = kp.getPrivate(),
                 identity = identity,
                 passPhrase = passPhrase)  
 }

 private def exportKeyPair(
      secretOut: OutputStream,
      publicOut: OutputStream,
      publicKey: java.security.PublicKey,
      privateKey: java.security.PrivateKey,
      identity: String,
      passPhrase: Array[Char]): Unit = {    
    // Create a new secret key.
    val secretKey = new PGPSecretKey(PGPSignature.DEFAULT_CERTIFICATION, 
                                     PublicKeyAlgorithmTags.RSA_GENERAL, 
                                     publicKey,
                                     privateKey,
                                     new Date(), 
                                     identity,
                                     SymmetricKeyAlgorithmTags.CAST5,
                                     passPhrase,
                                     null,
                                     null,
                                     new SecureRandom(),
                                     "BC")        
    secretKey.encode(secretOut)
    secretOut.close()
    val key = secretKey.getPublicKey()
    key.encode(publicOut)        
    publicOut.close()
  }
}