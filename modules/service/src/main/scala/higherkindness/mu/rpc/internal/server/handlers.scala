/*
 * Copyright 2017-2020 47 Degrees Open Source <https://www.47deg.com>
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

package higherkindness.mu.rpc.internal.server

import cats.effect.kernel._
import cats.effect.std.Dispatcher
// import cats.data.Kleisli
import cats.syntax.all._
import io.grpc._
// import io.grpc.ServerCall.Listener
import io.grpc.stub.ServerCalls
import io.grpc.stub.ServerCalls.UnaryMethod
import io.grpc.stub.{ServerCallStreamObserver, StreamObserver}
import higherkindness.mu.rpc.protocol.CompressionType
// import natchez.{EntryPoint, Span}

object handlers {

  def unary[F[_]: Async, Req, Res](
      f: Req => F[Res],
      compressionType: CompressionType,
      dispatcher: Dispatcher[F]
  ): ServerCallHandler[Req, Res] =
    ServerCalls.asyncUnaryCall(unaryMethod[F, Req, Res](f, compressionType, dispatcher))

  // def tracingUnary[F[_]: Spawn: Dispatcher, Req, Res](
  //     f: Req => Kleisli[F, Span[F], Res],
  //     methodDescriptor: MethodDescriptor[Req, Res],
  //     entrypoint: EntryPoint[F],
  //     compressionType: CompressionType,
  //     dispatcher: Dispatcher[F]
  // ): ServerCallHandler[Req, Res] =
  //   new ServerCallHandler[Req, Res] {
  //     def startCall(
  //         call: ServerCall[Req, Res],
  //         metadata: Metadata
  //     ): Listener[Req] = {
  //       val kernel = extractTracingKernel(metadata)
  //       val spanResource =
  //         entrypoint.continueOrElseRoot(methodDescriptor.getFullMethodName(), kernel)

  //       val method = unaryMethod[F, Req, Res](
  //         req => spanResource.use(span => f(req).run(span)),
  //         compressionType,
  //         dispatcher
  //       )

  //       ServerCalls.asyncUnaryCall(method).startCall(call, metadata)
  //     }
  //   }

  private def unaryMethod[F[_]: Async, Req, Res](
      f: Req => F[Res],
      compressionType: CompressionType,
      dispatcher: Dispatcher[F]
  ): UnaryMethod[Req, Res] =
    new UnaryMethodF[F, Req, Res](f, compressionType, dispatcher)

  // Spawn + Sync  -> Async ??? (conflicting instances otherwise)
  private class UnaryMethodF[F[_], Req, Res](
      f: Req => F[Res],
      compressionType: CompressionType,
      dispatcher: Dispatcher[F]
  )(implicit F: Async[F])
      extends UnaryMethod[Req, Res] {
    override def invoke(request: Req, observer: StreamObserver[Res]): Unit =
      dispatcher.unsafeRunAndForget(invokeF(request, observer))

    private def invokeF(request: Req, observer: StreamObserver[Res]): F[Unit] = {
      def cancelHandlerWhenCancelled(cancel: F[Unit]) = F.delay {
        observer
          .asInstanceOf[ServerCallStreamObserver[Res]]
          .setOnCancelHandler(() => dispatcher.unsafeRunAndForget(cancel))
      }

      runWithCancelation(addCompression(observer, compressionType) *> f(request))(
        cancelHandlerWhenCancelled
      ).flatMap {
        case Outcome.Succeeded(fvalue) => // F[Res] ?
          fvalue.map { value =>
            observer.onNext(value)
            observer.onCompleted()
          }
        case Outcome.Errored(t) =>
          val e = t match {
            case s: StatusException        => s
            case s: StatusRuntimeException => s
            case e                         => Status.INTERNAL.withDescription(e.getMessage).withCause(e).asException()
          }
          F.delay(observer.onError(e))
        case Outcome.Canceled() =>
          F.unit
      }
    }

    private def runWithCancelation[A](
        fa: F[A]
    )(registerCancelation: F[Unit] => F[Unit]): F[Outcome[F, Throwable, A]] =
      F.bracket(F.start(fa))(fiber => registerCancelation(fiber.cancel).as(fiber))(_ => F.unit)
        .flatMap(_.join)
  }
}
