/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package com.typesafe.coffeescript

import com.typesafe.jse.{Rhino, CommonNode, Node, Engine}
import com.typesafe.jse.Engine.{ExecuteJs, JsExecutionResult}
import com.typesafe.web.sbt.WebPlugin
import java.io.{InputStream, File}
import java.util.concurrent.TimeUnit
import _root_.sbt.IO
import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import spray.json._

final case class CompileArgs(
  coffeeScriptInputFile: File,
  javaScriptOutputFile: File,
  sourceMapOpts: Option[SourceMapOptions],
  bare: Boolean,
  literate: Boolean
)

/**
 * @param sourceMapOutputFile The file to write the source map to.
 * @param sourceMapRef A reference to .
 * @param javaScriptURL The URL of the source CoffeeScript files when served; can be absolute or relative to the map file.
 */
final case class SourceMapOptions(
  sourceMapOutputFile: File,
  sourceMapRef: String,
  javaScriptFileName: String,
  coffeeScriptRootRef: String,
  coffeeScriptPathRefs: List[String]
)

sealed trait CompileResult
final case object CompileSuccess extends CompileResult
final case class GenericError(message: String) extends CompileResult
final case class CodeError(
  message: String,
  lineContent: String,
  lineNumber: Int,
  lineOffset: Int
) extends CompileResult

final case class CoffeeScriptCompilerException(message: String) extends Exception(message)

object CoffeeScriptCompiler {

  def shellContent[A](f: InputStream => A): A = {
    val resource = "com/typesafe/coffeescript/driver.js"
    val classLoader = CoffeeScriptCompiler.getClass.getClassLoader
    val is = classLoader.getResourceAsStream(resource)
    try f(is) finally is.close()
  }

  def withShellFileCopiedTo(file: File): CoffeeScriptCompiler = {
    shellContent(is => IO.transfer(is, file))
    new CoffeeScriptCompiler(file)
  }

  object JsonConversion {
    import DefaultJsonProtocol._
    def toJson(args: CompileArgs): JsObject = {
      import args._
      JsObject(
        "coffeeScriptInputFile" -> JsString(coffeeScriptInputFile.getPath),
        "javaScriptOutputFile" -> JsString(javaScriptOutputFile.getPath),
        "sourceMapOpts" -> sourceMapOpts.fold[JsValue](JsNull)(toJson(_: SourceMapOptions)),
        "bare" -> JsBoolean(bare),
        "literate" -> JsBoolean(literate)
      )
    }
    def toJson(opts: SourceMapOptions): JsObject = {
      import opts._
      JsObject(
        "sourceMapOutputFile" -> JsString(sourceMapOutputFile.getPath),
        "sourceMapRef" -> JsString(sourceMapRef),
        "javaScriptFileName" -> JsString(javaScriptFileName),
        "coffeeScriptRootRef" -> JsString(coffeeScriptRootRef),
        "coffeeScriptPathRefs" -> JsArray(coffeeScriptPathRefs.map(JsString.apply))
      )
    }
    def fromJson(json: JsObject): CompileResult = {
      json.fields("result").asInstanceOf[JsString].value match {
        case "CompileSuccess" =>
          CompileSuccess
        case "CodeError" =>
          val message = json.fields("message").asInstanceOf[JsString].value
          val lineCode = json.fields("lineContent").asInstanceOf[JsString].value
          val lineNumber = json.fields("lineNumber").asInstanceOf[JsNumber].value.intValue
          val lineOffset = json.fields("lineOffset").asInstanceOf[JsNumber].value.intValue
          CodeError(message, lineCode, lineNumber, lineOffset)
        case "GenericError" =>
          GenericError(json.fields("message").asInstanceOf[JsString].value)
        case _ =>
          throw CoffeeScriptCompilerException(s"Unknown JSON result running CoffeeScript driver: $json")
      }
    }
  }

}

class CoffeeScriptCompiler(shellFile: File) {

  def compileFile(jsExecutor: JsExecutor, compileArgs: CompileArgs)(implicit ec: ExecutionContext): CompileResult = {

    import CoffeeScriptCompiler.JsonConversion

    val arg = JsonConversion.toJson(compileArgs).compactPrint

    val jsExecResult = jsExecutor.executeJsSync(Engine.ExecuteJs(shellFile, immutable.Seq(arg)))
    jsExecResult match {
      case JsExecutionResult(0, stdoutBytes, stderrBytes) if stderrBytes.length == 0 =>
        val jsonResult = (new String(stdoutBytes.toArray, "utf-8")).asJson.asInstanceOf[JsObject]
        JsonConversion.fromJson(jsonResult)
      case result =>
        val exitValue = result.exitValue
        val stdout = new String(result.output.toArray, "utf-8")
        val stderr = new String(result.error.toArray, "utf-8")
        throw CoffeeScriptCompilerException(s"Unexpected result running CoffeeScript driver: exit value: $exitValue, stdout: $stdout, stderr: $stderr")
    }
  }

}