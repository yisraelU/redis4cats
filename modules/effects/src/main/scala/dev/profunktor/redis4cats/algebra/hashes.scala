/*
 * Copyright 2018-2021 ProfunKtor
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

package dev.profunktor.redis4cats.algebra

import dev.profunktor.redis4cats.effects.ExpireExistenceArg

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

trait HashCommands[F[_], K, V]
    extends HashGetter[F, K, V]
    with HashSetter[F, K, V]
    with HashIncrement[F, K, V]
    with HashExpire[F, K] {
  def hDel(key: K, field: K, fields: K*): F[Long]
  def hExists(key: K, field: K): F[Boolean]
}

trait HashGetter[F[_], K, V] {
  def hGet(key: K, field: K): F[Option[V]]
  def hGetAll(key: K): F[Map[K, V]]
  def hmGet(key: K, field: K, fields: K*): F[Map[K, V]]
  def hKeys(key: K): F[List[K]]
  def hVals(key: K): F[List[V]]
  def hStrLen(key: K, field: K): F[Long]
  def hLen(key: K): F[Long]
}

trait HashSetter[F[_], K, V] {
  def hSet(key: K, field: K, value: V): F[Boolean]
  def hSet(key: K, fieldValues: Map[K, V]): F[Long]
  def hSetNx(key: K, field: K, value: V): F[Boolean]
  @deprecated("In favor of hSet(key: K, fieldValues: Map[K, V])", since = "1.0.1")
  def hmSet(key: K, fieldValues: Map[K, V]): F[Unit]
}

trait HashExpire[F[_], K] {
  def hExpire(key: K, expiresIn: FiniteDuration, fields: K*): F[List[Long]]
  def hExpire(key: K, expire: FiniteDuration, args: ExpireExistenceArg, fields: K*): F[List[Long]]
  def hExpireAt(key: K, expireAt: Instant, fields: K*): F[List[Long]]
  def hExpireAt(key: K, expireAt: Instant, args: ExpireExistenceArg, fields: K*): F[List[Long]]
  def hExpireTime(key: K, fields: K*): F[List[Option[Instant]]]
  def hpExpireTime(key: K, fields: K*): F[List[Option[Instant]]]
  def hPersist(key: K, fields: K*): F[List[Boolean]]
}

trait HashIncrement[F[_], K, V] {
  def hIncrBy(key: K, field: K, amount: Long): F[Long]
  def hIncrByFloat(key: K, field: K, amount: Double): F[Double]
}
