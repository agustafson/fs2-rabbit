---
layout: docs
title:  "Publisher"
number: 11
---

# Publisher

A `Publisher` is simply created by specifying a `ExchangeName` and a `RoutingKey`:

```tut:book:silent
import cats.effect.IO
import com.github.gvolpe.fs2rabbit.model._
import com.github.gvolpe.fs2rabbit.interpreter.Fs2Rabbit
import fs2._

val exchangeName = ExchangeName("testEX")
val routingKey   = RoutingKey("testRK")

def doSomething(publisher: String => IO[Unit]): Stream[IO, Unit] = Stream.eval(IO.unit)

def program(implicit R: Fs2Rabbit[IO, Stream]) =
  R.createConnectionChannel.flatMap { implicit channel => // Stream[IO, AMQPChannel]
    for {
      p <- R.createPublisher[String](exchangeName, routingKey)	  // String => IO[Unit]
      _ <- doSomething(p)
    } yield ()
  }
```

### Publishing a simple message

Once you have a `Publisher` you can start publishing messages by connecting a source `Stream[F, AmqpMessage]`:

```tut:book:silent
import cats.effect.Sync
import com.github.gvolpe.fs2rabbit.model._
import fs2._

def publishSimpleMessage[F[_]: Sync](publisher: String => F[Unit]): Stream[F, Unit] = {
  val message = "Hello world!"
  Stream(message).covary[F] evalMap publisher
}
```
