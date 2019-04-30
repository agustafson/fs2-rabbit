/*
 * Copyright 2017-2019 Gabriel Volpe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.gvolpe.fs2rabbit.interpreter

import cats.Functor
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.github.gvolpe.fs2rabbit.algebra.{BracketedResource, Connection}
import com.github.gvolpe.fs2rabbit.config.Fs2RabbitConfig
import com.github.gvolpe.fs2rabbit.effects.Log
import com.github.gvolpe.fs2rabbit.model.{AMQPChannel, AMQPConnection, RabbitChannel, RabbitConnection}
import com.rabbitmq.client.{Address, ConnectionFactory, Connection => RabbitMQConnection}
import javax.net.ssl.SSLContext
import scala.collection.JavaConverters._

class ConnectionBracketedResource[F[_], R[_[_], _]](
    factory: ConnectionFactory,
    addresses: NonEmptyList[Address]
)(implicit F: Sync[F], L: Log[F], B: BracketedResource[F, R], FR: Functor[Lambda[A => R[F, A]]])
    extends Connection[R[F, ?]] {

  private[fs2rabbit] val acquireConnection: F[RabbitMQConnection] =
    F.delay(factory.newConnection(addresses.toList.asJava))

  private[fs2rabbit] val acquireConnectionChannel: F[(RabbitMQConnection, AMQPChannel)] =
    for {
      conn    <- acquireConnection
      channel <- F.delay(conn.createChannel)
    } yield (conn, RabbitChannel(channel))

  override def createConnection: R[F, AMQPConnection] =
    B.acquireReleasable(acquireConnection)(closeConnection).map(RabbitConnection)

  /**
    * Creates a connection and a channel in a safe way using Stream.bracket.
    * In case of failure, the resources will be cleaned up properly.
    **/
  override def createConnectionChannel: R[F, AMQPChannel] =
    B.acquireReleasable(acquireConnectionChannel) {
        case (conn, RabbitChannel(channel)) =>
          F.delay { if (channel.isOpen) channel.close() } *> closeConnection(conn)
        case (_, _) => F.raiseError[Unit](new Exception("Unreachable"))
      }
      .map { case (_, channel) => channel }

  private def closeConnection(conn: RabbitMQConnection): F[Unit] =
    L.info(s"Releasing connection: $conn previously acquired.") *>
      F.delay { if (conn.isOpen) conn.close() }
}

object ConnectionBracketedResource {

  private[fs2rabbit] def mkConnectionFactory[F[_]: Sync](
      config: Fs2RabbitConfig,
      sslContext: Option[SSLContext]
  ): F[(ConnectionFactory, NonEmptyList[Address])] =
    Sync[F].delay {
      val factory   = new ConnectionFactory()
      val firstNode = config.nodes.head
      factory.setHost(firstNode.host)
      factory.setPort(firstNode.port)
      factory.setVirtualHost(config.virtualHost)
      factory.setConnectionTimeout(config.connectionTimeout)
      factory.setAutomaticRecoveryEnabled(config.automaticRecovery)
      if (config.ssl) {
        sslContext.fold(factory.useSslProtocol())(factory.useSslProtocol)
      }
      config.username.foreach(factory.setUsername)
      config.password.foreach(factory.setPassword)
      val addresses = config.nodes.map(node => new Address(node.host, node.port))
      (factory, addresses)
    }

}
