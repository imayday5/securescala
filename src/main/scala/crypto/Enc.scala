package crypto

import crypto.cipher._

import scalaz._
import scalaz.std.list._
import scalaz.std.math.bigInt._
import scalaz.syntax.order._

import argonaut._
import Argonaut._

final case class EncRatio(nominator: EncInt, denominator: EncInt)

sealed trait EncInt
class PaillierEnc(val underlying: BigInt, nSquare: BigInt) extends EncInt with Serializable {
  def +(that: PaillierEnc): PaillierEnc = (this,that) match {
    case (PaillierEnc(lhs),PaillierEnc(rhs)) =>
      new PaillierEnc((lhs * rhs).mod(nSquare), nSquare)
  }
  override def toString = s"PaillierEnc($underlying)"

  override def equals(that: Any) = that match {
    case PaillierEnc(underlying2) => underlying == underlying2
    case _ => false
  }
  override def hashCode = underlying.hashCode
}
class ElGamalEnc(val ca: BigInt, val cb: BigInt, p: BigInt) extends EncInt with Serializable {
  def *(that: ElGamalEnc): ElGamalEnc = (this,that) match {
    case (ElGamalEnc(ca1,ca2),ElGamalEnc(cb1,cb2)) =>
      new ElGamalEnc((ca1 * cb1).mod(p), (ca2 * cb2).mod(p), p)
  }
  override def toString = s"GamalEnc($ca,$cb)"
  override def equals(that: Any) = that match {
    case ElGamalEnc(ca2,cb2) => (ca,cb) == ((ca2,cb2))
    case _ => false
  }
  override def hashCode = ca.hashCode + cb.hashCode * 41
}
case class AesEnc(underlying: Array[Byte]) extends EncInt {
  override def equals(that: Any) = that match {
    case e@AesEnc(_) => Equal[AesEnc].equal(this,e)
    case _ => false
  }
}
case class OpeEnc(underlying: BigInt) extends EncInt

object PaillierEnc {
  implicit val paillierSemigroup = new Semigroup[PaillierEnc] {
    def append(f1: PaillierEnc, f2: => PaillierEnc): PaillierEnc = f1+f2
  }

  def unapply(p: PaillierEnc): Option[BigInt] = Some(p.underlying)
  def apply(k: Paillier.PubKey)(n: BigInt) = new PaillierEnc(n, k.nSquare)

  implicit val encode: EncodeJson[PaillierEnc] =
    EncodeJson((p: PaillierEnc) =>
      ("paillier" := p.underlying) ->: jEmptyObject)

  def decode(key: Paillier.PubKey): DecodeJson[PaillierEnc] =
    DecodeJson(c => (c --\ "paillier").as[BigInt].map(PaillierEnc(key)(_)))
}

object ElGamalEnc {
  implicit val gamalSemigroup = new Semigroup[ElGamalEnc] {
    def append(f1: ElGamalEnc, f2: => ElGamalEnc): ElGamalEnc = f1*f2
  }

  def unapply(eg: ElGamalEnc): Option[(BigInt,BigInt)] = Some((eg.ca, eg.cb))
  def apply(k: ElGamal.PubKey)(ca: BigInt, cb: BigInt) = new ElGamalEnc(ca, cb, k.p)

  implicit val encode: EncodeJson[ElGamalEnc] =
    EncodeJson((e: ElGamalEnc) =>
      ("elgamal_cb" := e.cb) ->: ("elgamal_ca" := e.ca) ->: jEmptyObject)

  def decode(key: ElGamal.PubKey): DecodeJson[ElGamalEnc] =
    DecodeJson(c => for {
      ca <- (c --\ "elgamal_ca").as[BigInt]
      cb <- (c --\ "elgamal_cb").as[BigInt]
    } yield ElGamalEnc(key)(ca,cb))
}

object AesEnc {
  implicit val aesEncEqual: Equal[AesEnc] = new Equal[AesEnc] {
    override def equal(a1: AesEnc, a2: AesEnc) = (a1,a2) match {
      case (AesEnc(x),AesEnc(y)) => x.size == y.size && (x,y).zipped.forall(_==_)
    }
  }

  implicit def aesCodec: CodecJson[AesEnc] = {
    CodecJson(
      (aes: AesEnc) =>
      ("aes_int" := aes.underlying.toList.map(_.toInt)) ->:
        jEmptyObject,
      c => (c --\ "aes_int").as[List[Int]].map(x=>AesEnc(x.toArray.map(_.toByte))))
  }
}

object OpeEnc {
  implicit val opeOrder = new Order[OpeEnc] {
    override def order(a: OpeEnc, b: OpeEnc): Ordering = (a,b) match {
      case (OpeEnc(x),OpeEnc(y)) => x ?|? y
    }
  }
  implicit val opeOrderScala = opeOrder.toScalaOrdering

  implicit def opeCodec: CodecJson[OpeEnc] =
    CodecJson(
      (ope: OpeEnc) =>
      ("ope_int" := ope.underlying) ->: jEmptyObject,
      c => (c --\ "ope_int").as[BigInt].map(OpeEnc(_)))
}

object EncInt {
  implicit val encode: EncodeJson[EncInt] = EncodeJson{
    case x@PaillierEnc(_) => PaillierEnc.encode(x)
    case x@ElGamalEnc(_,_) => ElGamalEnc.encode(x)
    case x@AesEnc(_) => AesEnc.aesCodec.Encoder(x)
    case x@OpeEnc(_) => OpeEnc.opeCodec.Encoder(x)
  }

  def decode(key: PubKeys): DecodeJson[EncInt] = DecodeJson { c =>
    PaillierEnc.decode(key.paillier)(c) match {
      case DecodeResult(Right(x)) => DecodeResult.ok(x)
      case DecodeResult(Left(_)) => ElGamalEnc.decode(key.elgamal)(c) match {
        case DecodeResult(Right(x)) => DecodeResult.ok(x)
        case DecodeResult(Left(_)) => AesEnc.aesCodec.Decoder(c) match {
          case DecodeResult(Right(x)) => DecodeResult.ok(x)
          case DecodeResult(Left(_)) => OpeEnc.opeCodec.Decoder(c) match {
            case DecodeResult(Right(x)) => DecodeResult.ok(x)
            case DecodeResult(Left(err)) => DecodeResult.fail(err._1,err._2)
          }
        }
      }
    }
  }
}

sealed trait EncString
case class AesString(underlying: Array[Byte]) extends EncString {
  override def equals(that: Any) = that match {
    case e@AesString(_) => Equal[AesString].equal(this,e)
    case _ => false
  }
}

case class OpeString(underlying: List[BigInt]) extends EncString

object AesString {
  implicit val aesStringEqual: Equal[AesString] = new Equal[AesString] {
    override def equal(a1: AesString, a2: AesString) = (a1,a2) match {
      case (AesString(x),AesString(y)) => x.size == y.size && (x,y).zipped.forall(_==_)
    }
  }

  implicit def aesStrCodec: CodecJson[AesString] = {
    CodecJson(
      (aes: AesString) =>
      ("aes_str" := aes.underlying.toList.map(_.toInt)) ->:
        jEmptyObject,
      c => (c --\ "aes_str").as[List[Int]].map(x=>AesString(x.toArray.map(_.toByte))))
  }
}

object OpeString {
  implicit val opeInstance = new Order[OpeString] with Semigroup[OpeString] {
    override def order(a: OpeString, b: OpeString): Ordering = (a,b) match {
      case (OpeString(x),OpeString(y)) => x ?|? y
    }

    override def append(fa: OpeString, fb: => OpeString) = (fa,fb) match {
      case (OpeString(xs),OpeString(ys)) => OpeString(xs ++ ys)
    }
  }

  implicit val opeStringOrderScala = opeInstance.toScalaOrdering

  implicit def opeCodec: CodecJson[OpeString] =
    CodecJson(
      (ope: OpeString) =>
      ("ope_str" := ope.underlying) ->:
        jEmptyObject,
      c => (c --\ "ope_str").as[List[BigInt]].map(OpeString(_)))
}

object EncString {
  implicit val encode: EncodeJson[EncString] = EncodeJson {
    case x@AesString(_) => AesString.aesStrCodec.Encoder(x)
    case x@OpeString(_) => OpeString.opeCodec.Encoder(x)
  }

  implicit val decode: DecodeJson[EncString] = DecodeJson { c =>
    OpeString.opeCodec.Decoder(c) match {
      case DecodeResult(Right(x)) => DecodeResult.ok(x)
      case DecodeResult(Left(_)) => AesString.aesStrCodec.Decoder(c) match {
        case DecodeResult(Right(x)) => DecodeResult.ok(x)
        case DecodeResult(Left(err)) => DecodeResult.fail(err._1,err._2)
      }
    }
  }
}

object EncRatio {
  implicit val encode: EncodeJson[EncRatio] = EncodeJson {
    case EncRatio(n,d) => (("enc_ratio_num" := n)
        ->: ("enc_ratio_den" := d) ->: jEmptyObject)
  }

  def decode(key: PubKeys): DecodeJson[EncRatio] = {
    implicit val normalDecoder = EncInt.decode(key)
    DecodeJson { c =>
      for {
        n <- (c --\ "enc_ratio_num").as[EncInt]
        d <- (c --\ "enc_ratio_den").as[EncInt]
      } yield EncRatio(n,d)
    }
  }
}
