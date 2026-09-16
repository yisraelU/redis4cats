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
import cats.syntax.all._

import scala.concurrent.duration._

import dev.profunktor.redis4cats.config.Redis4CatsConfig
import dev.profunktor.redis4cats.connection.{ RedisClient, RedisURI }
import dev.profunktor.redis4cats.data.{ RedisChannel, RedisCodec }
import dev.profunktor.redis4cats.effect.Log.NoOp._
import dev.profunktor.redis4cats.pubsub.PubSub
import io.lettuce.core.ClientOptions
import io.lettuce.core.resource.DefaultClientResources

/** Reproduces a known hazard: `Subscriber`'s Lettuce listener calls `dispatcher.unsafeRunSync(topic.publish1(msg))`
  * directly from Lettuce's Netty event-loop thread (verified against Lettuce 7.7.0 sources -
  * `PubSubEndpoint.notifyMessage` invokes every registered `RedisPubSubListener` synchronously, in-line, from
  * `CommandHandler.channelRead`). `fs2.concurrent.Topic.publish1` "semantically blocks" (per its own doc comment)
  * until a slow subscriber's bounded queue (`maxQueued`, `500` throughout this module) has room, and
  * `Dispatcher.unsafeRunSync` is implemented via `scala.concurrent.Await.result` (confirmed via a live thread
  * dump) - a real JVM thread park, not a cats-effect semantic suspension. Running the former inside the latter
  * permanently wedges the calling Netty thread once a slow subscriber's queue fills.
  *
  * Since a `RedisClient`'s Netty event-loop thread pool (from its `ClientResources`) is shared across every
  * connection opened from that same client, any *other* connection whose channel lands on that same wedged
  * thread (Netty assigns channels to event loops round-robin) is permanently stuck too - and critically, no
  * client-side `.timeout`/cancellation can rescue it: cancelling a channel-bound operation requires that
  * channel's own event-loop thread to run the cancellation (`SingleThreadEventExecutor`'s single-thread
  * contract), and a thread parked inside a foreign, synchronous `Await.result` has left Netty's dispatch loop
  * entirely, so nothing queued for it - including the cancellation itself - ever runs. This is also why this
  * test never tries to cancel the stuck connection: doing so would just hang the test too. It starts the stuck
  * attempt as an abandoned fiber and only ever passively checks whether it has finished on its own.
  */
class PubSubSlowConsumerBlocksNettyThreadReproSuite extends IOSuite {

  override val munitTimeout: Duration = 45.seconds

  test("REPRODUCER: a slow PubSub subscriber can permanently wedge unrelated connections sharing its event loop") {
    val channel = RedisChannel("pubsub-slow-consumer-repro")
    val codec   = RedisCodec.Utf8
    val uri     = RedisURI.unsafeFromString("redis://localhost")

    def log(msg: String): IO[Unit] = IO(println(s"[${System.currentTimeMillis()}] $msg"))

    // Lettuce enforces a minimum of 2 I/O threads (MIN_IO_THREADS in DefaultClientResources), so we can't force
    // a single shared thread - instead we rely on Netty's round-robin channel-to-eventloop assignment: enough
    // victim connections opened in sequence are guaranteed to cycle back onto the same (now wedged) event loop
    // as the pubsub connection. We don't hardcode which index that is (PubSub.mkPubSubConnection alone opens
    // two channels - a subscribe and a publish/stats connection - which already shifts the parity by one), we
    // just open enough victims to guarantee both event loops get hit and check for a mix of outcomes.
    val resources = DefaultClientResources.builder().ioThreadPoolSize(2).build()
    val config    = Redis4CatsConfig().withClientResources(resources)
    val victimCount = 4

    def openAndPing(client: RedisClient, i: Int): IO[Unit] =
      log(s"victim $i: acquiring connection") *>
        Redis[IO].fromClient(client, codec).use { redis =>
          log(s"victim $i: entering use, calling ping") *> redis.ping.void
        }

    val program =
      for {
        // Deliberately never released: gracefully shutting down this client would itself need the event loop
        // this test proves can become permanently unresponsive, so cleanup could hang forever too. Leaking it
        // is intentional here - this client is provably unusable by the end of the test regardless.
        clientAlloc <- RedisClient[IO].custom(uri, ClientOptions.create(), config).allocated
        (client, _) = clientAlloc

        pubSub <- PubSub.mkPubSubConnection[IO, String, String](client, codec).allocated.map(_._1)
        _ <- log("pubsub connection established")

        // A "slow consumer": pulls exactly one element, then hangs forever on it - never draining the bounded
        // queue backing this subscription again. Started via .start so the subscription is actually
        // established (Subscriber.subscribe is lazy: nothing happens until the returned Stream is compiled).
        _ <- pubSub.subscribe(channel).evalMap(_ => IO.never).compile.drain.start
        _ <- IO.sleep(300.millis) // let the SUBSCRIBE actually land before we start publishing
        _ <- log("slow consumer subscribed, starting publish loop")

        // Independent publisher, its own client/thread pool - isolates "is Redis itself fine" from "is our
        // shared thread pool wedged". Comfortably overflows the bounded queue (maxQueued = 500): the first
        // element is drained by the slow consumer, so ~500 more queue up before the channel's bound is hit and
        // a publish attempt semantically blocks inside the Netty listener.
        publishResult <- RedisClient[IO].from("redis://localhost").use { publisherClient =>
                           Redis[IO].fromClient(publisherClient, codec).use { publisher =>
                             (1 to 600).toList.traverse_(i => publisher.publish(channel, s"msg-$i"))
                           }
                         }.timeout(20.seconds).attempt
        _ <- log(s"publish loop result: $publishResult")

        // Each victim is started as an abandoned fiber - never joined-with-cancel or cancelled, since we've
        // shown that would just hang this test too (cancelling a channel-bound op needs that channel's own
        // event-loop thread, which is exactly what's wedged for whichever victims land on it).
        victimFibers <- (1 to victimCount).toList.traverse(i => openAndPing(client, i).start)
        _ <- IO.sleep(10.seconds)
        // A plain, non-cancelling peek per victim: waiting-for-join is a local cats-effect operation, so
        // timing out *this* doesn't require cancelling the victim itself, only giving up on watching it.
        outcomes <- victimFibers.traverse(_.join.timeout(1.second).attempt)
        stillRunning = outcomes.map(_.isLeft)
        _ <- log(s"victims still running after 10s: $stillRunning")
      } yield (publishResult, stillRunning)

    program.map { case (publishResult, stillRunning) =>
      assert(publishResult.isRight, s"expected the publish loop to complete, got $publishResult")
      assert(
        stillRunning.contains(false),
        s"expected at least one of $victimCount victims (on the healthy event loop) to complete normally, " +
          s"but all were still stuck after 10s: $stillRunning"
      )
      assert(
        stillRunning.contains(true),
        s"expected at least one of $victimCount victims to land on the wedged event loop and still be stuck " +
          s"after 10s, but all completed: $stillRunning - either the underlying bug is fixed, or not enough " +
          s"victims were opened to guarantee hitting the wedged thread this run"
      )
    }
  }
}
