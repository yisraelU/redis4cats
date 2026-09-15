/*
 * Copyright 2018-2025 ProfunKtor
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

import cats.effect.IO

import scala.concurrent.duration._

import dev.profunktor.redis4cats.data.RedisChannel

/** Reproduces a known race between `unsubscribe` and a concurrent `subscribe` on the same channel.
  *
  * `Subscriber.unsubscribeFrom` publishes `None` on the channel's `Topic` to terminate every existing subscriber's
  * stream, but it does not remove the channel's entry from the internal subscription map at that point - the entry
  * is only removed later, asynchronously, once each subscriber's stream actually observes that `None` and runs its
  * `onFinalize` action.
  *
  * If a `subscribe` call for the same channel lands in that window, it joins the map entry that's already
  * mid-termination and builds its stream from a *fresh* `Topic.subscribe`, made after the `None` was published.
  * `fs2.concurrent.Topic` never replays a value to a subscriber that joined after it was published, so the new
  * subscriber never receives that `None` - and since the underlying Redis subscription, Lettuce listener, and
  * dispatcher are concurrently being torn down by the terminating subscription's `cleanup`, it never receives
  * anything else either. Its stream hangs forever, and since it never finalizes, the map entry it incremented can
  * never reach zero subscribers again - the channel is permanently unable to be cleanly unsubscribed or
  * re-subscribed from a clean state.
  */
class PubSubUnsubscribeRaceReproSuite extends Redis4CatsFunSuite(isCluster = false) {

  test("REPRODUCER: a subscribe racing a concurrent unsubscribe can produce a stuck subscriber") {
    withRedisPubSub { pubSub =>
      val channel = RedisChannel("pubsub-unsubscribe-race-repro")

      for {
        // Establish the first (and, so far, only) subscriber for this channel.
        firstSub <- pubSub.subscribe(channel).compile.drain.start
        _ <- IO.sleep(300.millis) // let it fully register (Redis-level SUBSCRIBE ack included)
        _ <- pubSub.internalChannelSubscriptions.map(assertEquals(_, Map(channel -> 1L)))

        // unsubscribe publishes `None` and returns immediately - it does not wait for `firstSub` to observe
        // it or for the map entry to be removed. Racing a second `subscribe` in right behind it lands in
        // that window essentially every time, with no artificial delay needed.
        _ <- pubSub.unsubscribe(channel)
        secondSub <- pubSub.subscribe(channel).compile.drain.start

        firstOutcome <- firstSub.join.timeout(2.seconds).attempt
        secondOutcome <- secondSub.join.timeout(2.seconds).attempt
        subsAfter <- pubSub.internalChannelSubscriptions

        _ <- IO(
               println(
                 s"firstOutcome=$firstOutcome secondOutcome=$secondOutcome subsAfter=$subsAfter"
               )
             )

        // Expected (bug-free) behavior: both subscribers eventually terminate, and the map is empty again.
        // Demonstrated (buggy) behavior: `firstOutcome` completes, but `secondOutcome` times out - `secondSub`
        // is stuck forever - and `subsAfter` still shows an entry for `channel` that can never be cleared.
        _ <- secondSub.cancel // avoid leaking the stuck fiber past the end of this test
      } yield ()
    }
  }
}
