package lila.ws

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ ActorRef, Behavior }
import akka.http.scaladsl.model.ws.{ Message, TextMessage, WebSocketRequest }
import akka.http.scaladsl.model.headers.HttpCookiePair
import akka.stream.scaladsl._
import akka.stream.{ Materializer, OverflowStrategy }
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

import ipc._
import lila.ws.util.Util._

final class Server(
    auth: Auth,
    stream: Stream
)(implicit
    ec: ExecutionContext,
    system: akka.actor.ActorSystem,
    mat: Materializer
) {

  import Server._

  private val queues = stream.start

  private val bus = Bus(system)

  system.scheduler.schedule(30.seconds, 7211.millis) {
    bus publish Bus.msg(ClientCtrl.Broom(nowSeconds - 30), _.all)
  }

  def connectToSite(req: Request): Future[WebsocketFlow] =
    connectTo(req)(SiteClientActor.start) map asWebsocket(new RateLimit(
      maxCredits = 50,
      duration = 20.seconds,
      name = s"site ${req.name}"
    ))

  def connectToLobby(req: Request): Future[WebsocketFlow] = {
    connectTo(req)(LobbyClientActor.start) map asWebsocket(new RateLimit(
      maxCredits = 30,
      duration = 30.seconds,
      name = s"lobby ${req.name}"
    ))
  }

  private def connectTo(req: Request)(
    actor: ClientActor.Deps => Behavior[ClientMsg]
  ): Future[Flow[ClientOut, ClientIn, _]] =
    auth(req.authCookie) map { user =>
      actorFlow { clientIn =>
        actor {
          ClientActor.Deps(clientIn, queues, req, user, bus)
        }
      }
    }

  private def asWebsocket(limiter: RateLimit)(flow: Flow[ClientOut, ClientIn, _]): WebsocketFlow =
    Flow[Message] mapConcat {
      case TextMessage.Strict(text) if limiter(text) => ClientOut.parse(text).fold(_ => Nil, _ :: Nil)
      case _ => Nil
    } via flow via Flow[ClientIn].map { out =>
      TextMessage(out.write)
    }

  private def actorFlow(
    clientActor: SourceQueue[ClientIn] => Behavior[ClientMsg]
  ): Flow[ClientOut, ClientIn, _] = {

    import akka.actor.{ Status, Terminated, OneForOneStrategy, SupervisorStrategy }

    val (outQueue, publisher) = Source.queue[ClientIn](
      bufferSize = 8,
      overflowStrategy = OverflowStrategy.dropHead
    ).toMat(Sink.asPublisher(false))(Keep.both).run()

    Flow.fromSinkAndSource(
      Sink.actorRef(system.actorOf(akka.actor.Props(new akka.actor.Actor {
        val flowActor: ActorRef[ClientMsg] = context.spawn(clientActor(outQueue), "flowActor")
        context.watch(flowActor)

        def receive = {
          case Status.Success(_) | Status.Failure(_) => flowActor ! ClientCtrl.Disconnect
          case Terminated(_) => context.stop(self)
          case msg: ClientOut => flowActor ! msg
        }

        override def supervisorStrategy = OneForOneStrategy() {
          case _ => SupervisorStrategy.Stop
        }
      })), Status.Success(())),
      Source.fromPublisher(publisher)
    )
  }
}

object Server {

  type WebsocketFlow = Flow[Message, Message, _]

  case class Request(name: String, sri: Sri, flag: Option[Flag], authCookie: Option[HttpCookiePair])
}
