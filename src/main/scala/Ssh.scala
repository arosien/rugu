package com.novus.rugu

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import scala.util.control.Exception.allCatch
import java.io.{ByteArrayOutputStream, ByteArrayInputStream}

case class Host(name: String, port: Int = 22)

object Ssh {
  
  trait Executor {
    def apply[A](command: Command[_, A])(f: java.io.InputStream => A): Either[Throwable, (Option[Int], A, String)]
  }
  
  def apply(host: Host, auth: Authentication, knownHostsFile: Option[String] = None) = {
    val factoryBase =
      (_:Unit) => {
        val jsch = new SSHClient()
        knownHostsFile.foreach(f => jsch.loadKnownHosts(new java.io.File(f))) //FIXME don't load every time!
        jsch.connect(host.name, host.port)
        jsch
      }
    
    val factory = auth match {
      case UsernameAndPassword(u, p) =>
        new Executor {
          def apply[A](command: Command[_, A])(f: java.io.InputStream => A): Either[Throwable, (Option[Int], A, String)] = {
            allCatch.either {
              val ssh = factoryBase(())
              ssh.authPassword(u, p)
              val s = ssh.startSession()
              val c = s.exec(command.command)
              command.input.foreach { in => c.getOutputStream.write(in.getBytes()) }
              
              val err = scala.io.Source.fromInputStream(c.getErrorStream()).mkString
              c.join(5, java.util.concurrent.TimeUnit.SECONDS) //TODO
              val a = f(c.getInputStream())
              s.close()
              ssh.disconnect()
              (Option(c.getExitStatus).map(_.intValue), a, err)
            }
          }
        }
    }
    new SshSession(factory)
  }
}

class SshSession(factory: Ssh.Executor) {
  def apply[I, O](c: Command[I, O])(implicit sp: StreamProcessor[I]) =
    exec(c)(identity).fold(
      Left(_),
      { case (i, o, os) => Either.cond(i == Some(0), o, i -> os.toString) })
  
  def exec[I, O, OO](c: Command[I, O])(f: ((Option[Int], O, String)) => OO)(implicit sp: StreamProcessor[I]) =
    remote(c, sp).fold(Left(_), r => Right(f(r)))
  
  /* Get a new, authorized session and prepare a channel for invoking
   * shell commands. Yield the active session, (executed) channel, and a
   * closure to clean them up.
   */
  /* Left[Throwable] on network error. */
  def remote[I, O](c: Command[I, O], sp: StreamProcessor[I]): Either[Throwable, (Option[Int], O, String)] =
    factory(c) { sp andThen c }
}

object IO {
  type Resource = { def close(): Unit }
  def apply[A, R <: Resource](r: R)(op: R => A): Either[Throwable, A] =
    allCatch.andFinally(r.close()).either(op(r))
}
