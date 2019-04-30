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

package com.github.gvolpe.fs2rabbit.algebra

import cats.Functor
import cats.effect.{Resource => CatsResource}
import fs2.Stream

trait BracketedResource[F[_], R[_[_], _]] {
  def acquireReleasable[A](acquire: F[A])(release: A => F[Unit]): R[F, A]
}

trait BracketedResourceInstances {
  implicit def catsResourceBracketedResource[F[_]: Functor]: BracketedResource[F, CatsResource] =
    new BracketedResource[F, CatsResource] {
      override def acquireReleasable[A](acquire: F[A])(release: A => F[Unit]): CatsResource[F, A] =
        CatsResource.make(acquire)(release)
    }

  implicit def streamBracketedResource[F[_]]: BracketedResource[F, Stream] = new BracketedResource[F, Stream] {
    override def acquireReleasable[A](acquire: F[A])(release: A => F[Unit]): Stream[F, A] =
      Stream.bracket(acquire)(release)
  }
}

object BracketedResource extends BracketedResourceInstances
