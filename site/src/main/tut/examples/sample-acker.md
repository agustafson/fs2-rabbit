---
layout: docs
title:  "Single AckerConsumer"
number: 16
---

# Single AckerConsumer

Here we create a single `AckerConsumer`, a single `Publisher` and finally we publish two messages: a simple `String` message and a `Json` message by using the `fs2-rabbit-circe` extension.

```tut:book:silent
import cats.effect.Concurrent
import cats.Applicative
import cats.data.Kleisli
import cats.syntax.applicative._
import java.nio.charset.StandardCharsets.UTF_8
import com.github.gvolpe.fs2rabbit.config.declaration.DeclarationQueueConfig
import com.github.gvolpe.fs2rabbit.effects.StreamEval
import com.github.gvolpe.fs2rabbit.interpreter.Fs2Rabbit
import com.github.gvolpe.fs2rabbit.json.Fs2JsonEncoder
import com.github.gvolpe.fs2rabbit.model.AckResult.Ack
import com.github.gvolpe.fs2rabbit.model.AmqpHeaderVal.{LongVal, StringVal}
import com.github.gvolpe.fs2rabbit.model._
import com.github.gvolpe.fs2rabbit.effects.MessageEncoder
import fs2.{Pipe, Stream}

class Flow[F[_]: Concurrent](
  consumer: Stream[F, AmqpEnvelope[String]],
  acker: AckResult => F[Unit],
  logger: Pipe[F, AmqpEnvelope[String], AckResult],
  publisher: AmqpMessage[String] => F[Unit]
)(implicit SE: StreamEval[F]) {

  import io.circe.generic.auto._

  case class Address(number: Int, streetName: String)
  case class Person(id: Long, name: String, address: Address)

  private val jsonEncoder = new Fs2JsonEncoder[F]
  import jsonEncoder.jsonEncode

  val simpleMessage =
    AmqpMessage(
      "Hey!",
        AmqpProperties(headers = Map("demoId" -> LongVal(123), "app" -> StringVal("fs2RabbitDemo"))))
  val classMessage = AmqpMessage(Person(1L, "Sherlock", Address(212, "Baker St")), AmqpProperties.empty)

  val flow: Stream[F, Unit] =
    Stream(
      Stream(simpleMessage).covary[F] evalMap publisher,
      Stream(classMessage).covary[F] through jsonEncode[Person] evalMap publisher,
      consumer through logger evalMap acker
    ).parJoin(3)

}

class AckerConsumerDemo[F[_]: Concurrent](implicit F: Fs2Rabbit[F], SE: StreamEval[F]) {

  private val queueName    = QueueName("testQ")
  private val exchangeName = ExchangeName("testEX")
  private val routingKey   = RoutingKey("testRK")

  implicit val amqpMessageEncoder: MessageEncoder[F, AmqpMessage[String]] = Kleisli { msg =>
    msg.copy(payload = msg.payload.getBytes(UTF_8)).pure[F]
  }

  def logPipe: Pipe[F, AmqpEnvelope[String], AckResult] = { streamMsg =>
    for {
      amqpMsg <- streamMsg
      _       <- SE.evalF[Unit](println(s"Consumed: $amqpMsg"))
    } yield Ack(amqpMsg.deliveryTag)
  }

  val program: Stream[F, Unit] = F.createConnectionChannel.flatMap { implicit channel =>
    for {
      _                 <- F.declareQueue(DeclarationQueueConfig.default(queueName))
      _                 <- F.declareExchange(exchangeName, ExchangeType.Topic)
      _                 <- F.bindQueue(queueName, exchangeName, routingKey)
      (acker, consumer) <- F.createAckerConsumer[String](queueName)
      publisher         <- F.createPublisher[AmqpMessage[String]](exchangeName, routingKey)
      result            <- new Flow(consumer, acker, logPipe, publisher).flow
    } yield result
  }

}
```

At the edge of out program we define our effect, `cats.effect.IO` in this case, and ask to evaluate the effects:

```tut:book:silent
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.functor._
import com.github.gvolpe.fs2rabbit.config.Fs2RabbitConfig
import com.github.gvolpe.fs2rabbit.interpreter.Fs2Rabbit
import com.github.gvolpe.fs2rabbit.resiliency.ResilientStream

object IOAckerConsumer extends IOApp {

  private val config: Fs2RabbitConfig = Fs2RabbitConfig(
    virtualHost = "/",
    host = "127.0.0.1",
    username = Some("guest"),
    password = Some("guest"),
    port = 5672,
    ssl = false,
    connectionTimeout = 3,
    requeueOnNack = false,
    internalQueueSize = Some(500)
  )

  override def run(args: List[String]): IO[ExitCode] =
    Fs2Rabbit.stream[IO](config).flatMap { implicit fs2Rabbit =>
      ResilientStream.run(new AckerConsumerDemo[IO]().program)
        .as(ExitCode.Success)
    }
}
```
