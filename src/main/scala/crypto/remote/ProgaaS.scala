package crypto.remote

import com.typesafe.config.ConfigFactory

import akka.pattern.ask
import akka.util.Timeout

import scala.util.Try
import scala.io.StdIn

import scalaz._

import scala.concurrent._
import scala.concurrent.duration._

import crypto._
import crypto.dsl._
import crypto.cipher._

trait ProgaaS {
  implicit def ec: ExecutionContext
  def name: String
  def program(in: EncInt): CryptoM[EncInt]

  def run(): Unit = {
    print("Trying to connect to crypto service...")
    val (system,futService) = CryptoService.connect
    val service = \/.fromTryCatchNonFatal(Await.result(futService, 10.seconds)) match {
      case -\/(err) =>
        println("failed!")
        println(s"Error connecting to crypto service: ${err}")
        println("Did you start the crypto service before running this?")
        system.terminate()
        sys.exit(1)
      case \/-(service) =>
        println("ok")
        service
    }

    print("Requesting public keys...")
    val keys: PubKeys = Await.result(service.publicKeys, 10.seconds)
    println("ok")

    val remoteInterpreter = new RemoteInterpreter(service, keys)

    def loop(): Unit = {
      println(s"Your input for program '${name}':")
      val input = StdIn.readLine
      if (input.startsWith("quit")) {
        system.terminate()
        sys.exit(0)
      } else {
        \/.fromTryCatchNonFatal(input.toInt) match {
          case \/-(i) =>
            Common.encryptPub(Additive, keys)(i) match {
              case -\/(err) => println("Failed during public key encryption with: " + err)
              case \/-(encryptedInput) =>
                val result = remoteInterpreter.interpret {
                  program(encryptedInput)
                }
                val finalRes = Await.result(result, 60.minutes)
                service.println(s"Result for input ${i} and program ${name}:")
                service.decryptAndPrint(finalRes)
            }
          case -\/(e) => "Invalid input, type `quit` to exit"
        }
        loop
      }
    }

    loop
  }
}

object FactaaS extends ProgaaS with App {
  def ec = scala.concurrent.ExecutionContext.Implicits.global
  def name = "factorial"
  def program(in: EncInt) = ExamplePrograms.factorial(in)

  run
}

object FibaaS extends ProgaaS with App {
  def ec = scala.concurrent.ExecutionContext.Implicits.global
  def name = "fibonacci"
  def program(in: EncInt) = ExamplePrograms.fib(in)

  run
}

object CollatzaaS extends ProgaaS with App {
  def ec = scala.concurrent.ExecutionContext.Implicits.global
  def name = "collatz conjecture"
  def program(in: EncInt) = ExamplePrograms.collatzConjecture(in)

  run
}
