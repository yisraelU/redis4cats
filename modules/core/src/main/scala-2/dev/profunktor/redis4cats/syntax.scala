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

package dev.profunktor.redis4cats

import dev.profunktor.redis4cats.connection.RedisURI

object syntax extends syntax {
  class RedisURIOps(val sc: StringContext) extends AnyVal {
    def redis(args: Any*): RedisURI = macro macros.redis.make
  }
}

trait syntax {
  implicit def toRedisURIOps(sc: StringContext): syntax.RedisURIOps =
    new syntax.RedisURIOps(sc)
}
