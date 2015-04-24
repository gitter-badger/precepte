package com.mfglabs.monitoring

import scala.language.higherKinds
import scalaz.{ Bind, Monad, Applicative, Functor, \/, \/-, -\/, IndexedStateT }
import scalaz.syntax.monad._
import Call.{ Env, Tags }

sealed trait Precepte[E <: Env, T <: Tags, C, F[_], A] {
  self =>

  final def flatMap[B](f: A => Precepte[E, T, C, F, B]): Precepte[E, T, C, F, B] =
    Flatmap[E, T, C, F, A, B](self, f)

  final def map[B](f: A => B): Precepte[E, T, C, F, B] =
    flatMap(a => Return(f(a)))

  def lift[AP[_]](implicit ap: Applicative[AP], fu: Functor[F]): Precepte[E, T, C, F, AP[A]] =
    this.map(a => ap.point(a))

  final def eval(state: Call.State[E, T, C], ids: Stream[Call.Id] = Stream.continually(Call.Id.gen))(implicit mo: Monad[F]): F[A] = {
    this match {
      case Return(a) => a.point[F]
      case Step(st, tags) =>
        val state0 = state.copy(path = state.path :+ Call(ids.head, tags))
        st.run(state0).flatMap { case (c, m) =>
          m.eval(state0.copy(value = c), ids.tail)
        }
      case Flatmap(sub, next) =>
        sub.eval(state).flatMap { case i =>
          next(i).eval(state)
        }
    }
  }

  final def run(state: Call.State[E, T, C], ids: Stream[Call.Id] = Stream.continually(Call.Id.gen))(implicit mo: Monad[F]): F[(Call.Root[T, C], A)] = {
    def go[G <: Call.Graph[T, C, G] ,B](m: Precepte[E, T, C, F, B], state: Call.State[E, T, C], graph: G, ids: Stream[Call.Id]): F[(Stream[Call.Id], (G, B))] = {
      m match {
        case Return(a) =>
          (ids, (graph, a)).point[F]
        case Step(step, tags) =>
          val state0 = state.copy(path = state.path :+ Call(ids.head, tags))
          step.run(state0).flatMap {
            case (c, mc) =>
              val id = ids.head
              val g0 = Call.GraphNode(id, c, tags, Vector.empty)
              go(mc, state0.copy(value = c), g0, ids.tail).map { case (is, (g, a)) =>
                (is, (graph.addChild(g),  a))
              }
          }
        case Flatmap(sub, next) =>
          // XXX: kinda hackish. We're only interested in this node children
          val g0 = Call.Root[T, C](Call.Span("dummy"), Vector.empty)
          go(sub, state, g0, ids).flatMap { case (is0, (gi, i)) =>
            go(next(i), state, gi, is0).map { case (is1, (g, a)) =>
              (is1, (graph.addChildren(g.children), a))
            }
          }
      }
    }
    go(this, state, Call.Root[T, C](state.span, Vector.empty), ids).map(_._2)
  }

}

private case class Return[E <: Env, T <: Tags, C, F[_], A](a: A) extends Precepte[E, T, C, F, A]
private case class Step[E <: Env, T <: Tags, C, F[_], A](st: IndexedStateT[F, Call.State[E, T, C], C, Precepte[E, T, C, F, A]], tags: T) extends Precepte[E, T, C, F, A] {
  def run(state: Call.State[E, T, C]): F[(C, Precepte[E, T, C, F, A])] =
    st.run(state)
}

private case class Flatmap[E <: Env, T <: Tags, C, F[_], I, A](sub: Precepte[E, T, C, F, I], next: I => Precepte[E, T, C, F, A]) extends Precepte[E, T, C, F, A]

object Precepte {

  trait PrecepteBuilder[T <: Tags] {
    val tags: T
    import scalaz.Id._

    def apply0[E <: Env, C, A](λ: Call.State[E, T, C] => A): Precepte[E, T, C, Id, A] =
      apply[E, C, Id, A](λ)

    def apply[E <: Env, C, F[_]: Functor, A](λ: Call.State[E, T, C] => F[A]): Precepte[E, T, C, F, A] =
      Step[E, T, C, F, A](
        IndexedStateT { (st: Call.State[E, T, C]) =>
          for (a <- λ(st))
          yield st.value -> Return(a)
        }, tags)

    def applyS[E <: Env, C, F[_]: Functor, A](λ: Call.State[E, T, C] => F[(C, A)]): Precepte[E, T, C, F, A] =
      Step[E, T, C, F, A](
        IndexedStateT { (st: Call.State[E, T, C]) =>
          for (ca <- λ(st))
          yield {
            val (c, a) = ca
            c -> Return(a)
          }
        }, tags)

    def apply[E <: Env, C, F[_]: Applicative, A](m: Precepte[E, T, C, F, A]): Precepte[E, T, C, F, A] =
      Step(IndexedStateT[F, Call.State[E, T, C], C, Precepte[E, T, C, F, A]]{ st =>
        (st.value -> m).point[F]
      }, tags)
  }

  def apply[T <: Tags](_tags: T) =
    new PrecepteBuilder[T] {
      val tags = _tags
    }

  trait *->*[F[_]] {}
  trait *->*->*[F[_, _]] {}

  implicit def fKindEv[F0[_]] = new *->*[F0] {}
  implicit def fKindEv2[F0[_, _]] = new *->*->*[F0] {}

  def trans[E <: Env, T <: Tags, C, F[_], G[_]: *->*, A](m: Precepte[E, T, C, F, G[A]])(implicit hh: HasHoist[G]): hh.T[({ type λ[α] = Precepte[E, T, C, F, α] })#λ, A] = {
    type λ[α] = Precepte[E, T, C, F, α]
    hh.lift[λ, A](m)
  }

  def trans[E <: Env, T <: Tags, C, F[_], G[_, _]: *->*->*, A, B](m: Precepte[E, T, C, F, G[A, B]])(implicit hh: HasHoist[({ type λ[α] = G[A, α] })#λ]): hh.T[({ type λ[α] = Precepte[E, T, C, F, α] })#λ, B] = {
    type λ[α] = G[A, α]
    trans[E, T, C, F, λ, B](m)(new *->*[λ] {}, hh)
  }

  implicit def PrecepteInstances[E <: Env, T <: Tags, C, F[_]: Bind] =
    new Monad[({ type λ[α] = Precepte[E, T, C, F, α] })#λ] {
      override def point[A](a: => A): Precepte[E,T,C,F,A] =
        Return(a)
      override def map[A, B](m: Precepte[E, T, C, F, A])(f: A => B): Precepte[E,T,C,F,B] =
        m.map(f)
      override def bind[A, B](m: Precepte[E, T, C, F, A])(f: A => Precepte[E, T, C, F, B]): Precepte[E, T, C, F, B] =
        m.flatMap(f)
    }
}