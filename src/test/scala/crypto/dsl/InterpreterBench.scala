package crypto.dsl

import scala.language.higherKinds

import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import scalaz.std.list._

import org.scalacheck.{Gen => SCGen}

import org.scalameter.api._

import crypto._
import crypto.dsl.Implicits._
import crypto.cipher._
import crypto.remote._

trait InterpreterBench[F[_]] {
  this: PerformanceTest =>

  val keyRing = KeyRing.create
  def generators = EncryptedGens(keyRing)

  val zero = Common.zero(keyRing)
  val one = Common.one(keyRing)

  val sizes = Gen.enumeration("size")(2,4,6,8,10)
  val lists =
    for (size <- sizes) yield SCGen.listOfN(size, generators.encryptedNumber).sample.get

  // To be implemented
  def finalize[A]: F[A] => A
  def interpret[A]: CryptoM[A] => F[A]
  def name: String

  // The specific test cases
  performance of name in {
    measure method "monadic sum" in {
      using(lists) in { xs => finalize { interpret(sumM(zero)(xs)) } }
    }

    measure method "applicative sum" in {
      using(lists) in { xs => finalize { interpret(sumA(zero)(xs)) } }
    }

    measure method "monadic product" in {
      using(lists) in { xs => finalize { interpret(productM(one)(xs)) } }
    }

    measure method "applicative product" in {
      using(lists) in { xs => finalize { interpret(productA(one)(xs)) } }
    }

    measure method "sorting" in {
      using(lists) in { xs => finalize { interpret(sorted(xs)) } }
    }
  }
}

class RemoteInterpreterOptAnalyzeBench
    extends CustomPerformanceTest
    with InterpreterBench[Future] {

  def name = "Remote interpreter with opt + analysis"
  @transient val cryptoService = new CryptoServiceImpl(keyRing)(CustomExecutionContext(5))

  val pubKeys = Await.result(cryptoService.publicKeys, 10.seconds)

  @transient val interpreter =
    new RemoteInterpreterOptAnalyze(cryptoService, pubKeys, FixedBatch(20), _ >= 10)(
      ExecutionContext.Implicits.global)

  override def interpret[A] = (x: CryptoM[A]) => interpreter.interpret(x)
  override def finalize[A] = (x: Future[A]) => Await.result(x,Duration.Inf)
}

class RemoteInterpreterOptBench
    extends CustomPerformanceTest
    with InterpreterBench[Future] {

  def name = "Remote interpreter with opt"
  @transient val cryptoService = new CryptoServiceImpl(keyRing)(CustomExecutionContext(5))

  val pubKeys = Await.result(cryptoService.publicKeys, 10.seconds)

  @transient val interpreter =
    new RemoteInterpreterOpt(cryptoService, pubKeys)(ExecutionContext.Implicits.global)

  override def interpret[A] = (x: CryptoM[A]) => interpreter.interpret(x)
  override def finalize[A] = (x: Future[A]) => Await.result(x,Duration.Inf)
}

class RemoteInterpreterBench
    extends CustomPerformanceTest
    with InterpreterBench[Future] {

  def name = "Remote interpreter"
  @transient val cryptoService = new CryptoServiceImpl(keyRing)(CustomExecutionContext(5))

  val pubKeys = Await.result(cryptoService.publicKeys, 10.seconds)

  @transient val interpreter =
    new RemoteInterpreter(cryptoService, pubKeys)(ExecutionContext.Implicits.global)

  override def interpret[A] = (x: CryptoM[A]) => interpreter.interpret(x)
  override def finalize[A] = (x: Future[A]) => Await.result(x,Duration.Inf)
}

class LocalInterpreterBench
    extends CustomPerformanceTest
    with InterpreterBench[λ[α=>α]] {

  def name = "Local interpreter"
  val interpreter = LocalInterpreter(keyRing)

  override def interpret[A] = (x: CryptoM[A]) => interpreter.interpret(x)
  override def finalize[A] = (x: A) => x
}

class InterpreterBenchSuite extends CustomPerformanceTest {
  include[LocalInterpreterBench]
  include[RemoteInterpreterBench]
  include[RemoteInterpreterOptBench]
  include[RemoteInterpreterOptAnalyzeBench]
}

object InterpreterBenchSuiteRunner extends App {
  val bench = new InterpreterBenchSuite
  bench.main(args)
}
