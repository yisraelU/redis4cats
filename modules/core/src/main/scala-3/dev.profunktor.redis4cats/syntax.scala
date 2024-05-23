package dev.profunktor.redis4cats

import dev.profunktor.redis4cats.connection.RedisURI
import org.typelevel.literally.Literally
import scala.language.`3.0`

trait LiteralsSyntax {
  extension (inline ctx: StringContext) {
    inline def redis(inline args: Any*): RedisURI = ${ LiteralsSyntax.RedisLiteral('ctx, 'args) }
}

 object LiteralsSyntax extends LiteralsSyntax {
        object RedisLiteral extends Literally[Uri] {
         def validate(s: String)(using Quotes):Either[String,RedisURI] = {
           RedisURI.fromString(s) match {
             case Left(e)  => Left(e.getMessage)
             case Right(_) => Right('{ RedisURI.unsafeFromString(${ Expr(s) }) })
           }
         }
        }
 }
