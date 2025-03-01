package scala.quoted

class Expr[T] extends Quoted {
  def unary_~ : T = ???
  def run: T = ???
}

object Expr {
  implicit def toExpr[T](x: T)(implicit ev: Liftable[T]): Expr[T] =
    ev.toExpr(x)

  implicit class AsFunction[T, U](private val f: Expr[T => U]) extends AnyVal {
    def apply(x: Expr[T]): Expr[U] = ???
  }
}
