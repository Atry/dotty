import scala.deriving._
import scala.quoted._
import scala.quoted.matching._
import scala.compiletime.{erasedValue, summonFrom}
import JsonEncoder.{given _, _}

object SummonJsonEncoderTest {

  inline def encodeAndMessAroundType[T](value: =>T): String = ${ encodeAndMessAroundTypeImpl('value) }

  def encodeAndMessAroundTypeImpl[T: Type](value: Expr[T])(given qctx: QuoteContext): Expr[String] = {
    import qctx.tasty._

    val mirrorExpr = summonExpr[Mirror.Of[T]] match {
      case Some(mirror) => mirror
    }

    '{
      given JsonEncoder[T] = JsonEncoder.derived($mirrorExpr)
      val encoder = summon[JsonEncoder[T]]
      encoder.encode($value)
    }
  }
}