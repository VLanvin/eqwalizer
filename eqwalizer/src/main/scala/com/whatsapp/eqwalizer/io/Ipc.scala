/* Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.
 *
 * This source code is licensed under the Apache 2.0 license found in
 * the LICENSE file in the root directory of this source tree.
 */

package com.whatsapp.eqwalizer.io
import com.whatsapp.eqwalizer.util.ELPDiagnostics

object Ipc {
  case object Terminated extends Exception
  private case class GotNull() extends Exception

  sealed trait ASTFormat {
    val jsonName: String
  }
  case object RawForms extends ASTFormat { val jsonName = "RawForms" }
  case object ConvertedForms extends ASTFormat { val jsonName = "ConvertedForms" }
  case object RawStubs extends ASTFormat { val jsonName = "RawStubs" }
  case object ConvertedStubs extends ASTFormat { val jsonName = "ConvertedStubs" }

  def getAstBytes(module: String, kind: ASTFormat): Option[Array[Byte]] = {
    send(GetAstBytes(module, kind))
    receive() match {
      case Right(GetAstBytesReply(astBytesLen)) if astBytesLen == 0 =>
        println()
        Console.out.flush()
        None
      case Right(GetAstBytesReply(astBytesLen)) =>
        println()
        Console.out.flush()
        val buf = new Array[Byte](astBytesLen)
        val read = readNBytes(System.in, buf, 0, astBytesLen)
        assert(read == astBytesLen, s"expected $astBytesLen for $module but got $read")
        Some(buf)
      case Right(CannotCompleteRequest) =>
        // The client has asked eqWAlizer to die
        throw Terminated
      case Left(GotNull()) =>
        // Happens when the client panics, such as ELP bug T111364923.
        // This error will only show up in logging, not really user-facing
        Console.err.println(s"eqWAlizer could not read AST for $module")
        throw Terminated
    }
  }

  def sendDone(diagnostics: collection.Map[String, List[ELPDiagnostics.Error]]): Unit =
    send(Done(diagnostics))

  def sendEqwalizingStart(module: String): Unit =
    send(EqwalizingStart(module))

  def sendEqwalizingDone(module: String): Unit =
    send(EqwalizingDone(module))

  def send(req: Request): Unit = {
    val json = reqToJson(req)
    json.writeBytesTo(Console.out)
    Console.out.println()
    Console.flush()
  }

  private def receive(): Either[GotNull, Reply] = {
    val str = Console.in.readLine()
    if (str == null) {
      Left(GotNull())
    } else {
      val json = ujson.read(str)
      Right(jsonToReply(json))
    }
  }

  private def reqToJson(req: Request): ujson.Obj = req match {
    case GetAstBytes(module, format) =>
      ujson.Obj(
        "tag" -> "GetAstBytes",
        "content" -> ujson.Obj(
          "module" -> module,
          "format" -> format.jsonName,
        ),
      )
    case EqwalizingStart(module) =>
      ujson.Obj(
        "tag" -> "EqwalizingStart",
        "content" -> ujson.Obj(
          "module" -> module
        ),
      )
    case EqwalizingDone(module) =>
      ujson.Obj(
        "tag" -> "EqwalizingDone",
        "content" -> ujson.Obj(
          "module" -> module
        ),
      )
    case Done(diagnostics) =>
      ujson.Obj(
        "tag" -> ujson.Str("Done"),
        "content" ->
          ujson.Obj(
            "diagnostics" -> ELPDiagnostics.toJsonObj(diagnostics)
          ),
      )
  }

  private def jsonToReply(value: ujson.Value): Reply =
    value.obj("tag") match {
      case ujson.Str("GetAstBytesReply") =>
        val content = value.obj("content").obj
        val astBytesLen = content("ast_bytes_len").num.toInt
        GetAstBytesReply(astBytesLen)
      case ujson.Str("CannotCompleteRequest") =>
        CannotCompleteRequest
      case _ =>
        throw new IllegalStateException(s"unexpected reply $value")
    }

  // copy/pasted from java.io.InputStream.readNBytes and then Scalafied.
  // replace with stream.readNBytes in T111884043
  private def readNBytes(stream: java.io.InputStream, b: Array[Byte], off: Int, len: Int): Int = {
    var n = 0;

    while (n < len) {
      val count = stream.read(b, off + n, len - n);
      if (count < 0)
        return n
      n += count;
    }
    n
  }

  private sealed trait Request
  private case class GetAstBytes(module: String, format: ASTFormat) extends Request
  private case class EqwalizingStart(module: String) extends Request
  private case class EqwalizingDone(module: String) extends Request
  private case class Done(diagnostics: collection.Map[String, List[ELPDiagnostics.Error]]) extends Request

  private sealed trait Reply

  /**
    * This is the only non-JSON part of the protocol.
    * After receiving this message, eqWAlizer prints a newline to stdout
    * and then reads `astBytesLen` bytes from stdin
    */
  private case class GetAstBytesReply(astBytesLen: Int) extends Reply
  private case object CannotCompleteRequest extends Reply
}
