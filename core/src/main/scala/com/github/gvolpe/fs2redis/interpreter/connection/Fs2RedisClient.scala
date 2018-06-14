/*
 * Copyright 2018 Fs2 Redis
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

package com.github.gvolpe.fs2redis.interpreter.connection

import cats.effect.{Concurrent, Resource}
import cats.syntax.apply._
import com.github.gvolpe.fs2redis.model.{DefaultRedisClient, Fs2RedisClient}
import com.github.gvolpe.fs2redis.util.JRFuture
import fs2.Stream
import io.lettuce.core.{RedisClient, RedisURI}

object Fs2RedisClient {

  def apply[F[_]](uri: RedisURI)(implicit F: Concurrent[F]): Resource[F, Fs2RedisClient] = {
    val acquire: F[Fs2RedisClient] = F.delay { DefaultRedisClient(RedisClient.create(uri)) }
    val release: Fs2RedisClient => F[Unit] = client =>
      JRFuture.fromCompletableFuture(F.delay(client.underlying.shutdownAsync())) *>
        F.delay(s"Releasing Redis connection: ${client.underlying}")

    Resource.make(acquire)(release)
  }

  def stream[F[_]](uri: RedisURI)(implicit F: Concurrent[F]): Stream[F, Fs2RedisClient] = {
    val acquire = F.delay { DefaultRedisClient(RedisClient.create(uri)) }
    val release: Fs2RedisClient => F[Unit] = client =>
      JRFuture.fromCompletableFuture(F.delay(client.underlying.shutdownAsync())) *>
        F.delay(s"Releasing Redis connection: ${client.underlying}")

    Stream.bracket(acquire)(release)
  }

}