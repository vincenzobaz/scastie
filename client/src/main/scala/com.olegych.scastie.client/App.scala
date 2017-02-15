package com.olegych.scastie
package client

import japgolly.scalajs.react._, vdom.all._, extra.router.RouterCtl

import api._
import autowire._
import scalajs.concurrent.JSExecutionContext.Implicits.queue

import org.scalajs.dom.console
import org.scalajs.dom._

import scala.util.{Success, Failure}

import upickle.default.{read => uread, ReadWriter, macroRW => upickleMacroRW}

object App {

  case class Props(
      router: Option[RouterCtl[Page]],
      snippet: Option[Snippet],
      embedded: Option[EmbededOptions]
  ) {
    def isEmbedded: Boolean = embedded.isDefined
  }

  object State {
    def default = State(
      view = View.Editor,
      running = false,
      eventSource = None,
      websocket = None,
      isDarkTheme = false,
      consoleIsOpen = false,
      consoleHasUserOutput = false,
      inputs = Inputs.default,
      outputs = Outputs.default
    )

    def dontSerialize[T]: ReadWriter[Option[T]] = {
      import upickle.Js
      ReadWriter[Option[T]](_ => Js.Null, { case _ => None })
    }

    implicit val dontSerializeWebSocket = dontSerialize[WebSocket]
    implicit val dontSerializeEventSource = dontSerialize[EventSource]
    implicit val pkl: ReadWriter[State] = upickleMacroRW[State]
  }

  case class State(
      view: View,
      running: Boolean,
      eventSource: Option[EventSource],
      websocket: Option[WebSocket],
      isDarkTheme: Boolean,
      consoleIsOpen: Boolean,
      consoleHasUserOutput: Boolean,
      inputs: Inputs,
      outputs: Outputs
  ) {
    def copyAndSave(view: View = view,
                    running: Boolean = running,
                    eventSource: Option[EventSource] = eventSource,
                    websocket: Option[WebSocket] = websocket,
                    isDarkTheme: Boolean = isDarkTheme,
                    consoleIsOpen: Boolean = consoleIsOpen,
                    consoleHasUserOutput: Boolean = consoleHasUserOutput,
                    inputs: Inputs = inputs,
                    outputs: Outputs = outputs): State = {

      val state0 =
        copy(view,
             running,
             eventSource,
             websocket,
             isDarkTheme,
             consoleIsOpen,
             consoleHasUserOutput,
             inputs,
             outputs)

      LocalStorage.save(state0)

      state0
    }

    def isClearable: Boolean = outputs.isClearable

    def setRunning(running: Boolean) = {
      val console = !running && !consoleHasUserOutput
      copyAndSave(running = running, consoleIsOpen = !console)
    }

    def toggleTheme = copyAndSave(isDarkTheme = !isDarkTheme)
    def toggleConsole = copyAndSave(consoleIsOpen = !consoleIsOpen)
    def toggleScriptMode =
      copyAndSave(
        inputs = inputs.copy(scriptMode = !inputs.scriptMode))

    def openConsole = copyAndSave(consoleIsOpen = true)
    def setUserOutput = copyAndSave(consoleHasUserOutput = true)

    def log(line: String): State = log(Seq(line))
    def log(lines: Seq[String]): State =
      copyAndSave(outputs = outputs.copy(console = outputs.console ++ lines))
    def log(line: Option[String]): State =
      line match {
        case Some(l) => log(l + "\n")
        case None => this
      }

    def setCode(code: String) = copyAndSave(inputs = inputs.copy(code = code))
    def setInputs(inputs: Inputs) = copyAndSave(inputs = inputs)
    def setSbtConfigExtra(config: String) =
      copyAndSave(inputs = inputs.copy(sbtConfigExtra = config))
    def setView(newView: View) = copyAndSave(view = newView)
    def setTarget(target: ScalaTarget) =
      copyAndSave(inputs = inputs.copy(target = target))

    def addScalaDependency(scalaDependency: ScalaDependency) =
      copyAndSave(
        inputs = inputs.copy(libraries = inputs.libraries + scalaDependency))

    def removeScalaDependency(scalaDependency: ScalaDependency) =
      copyAndSave(
        inputs = inputs.copy(libraries = inputs.libraries - scalaDependency))

    def changeDependencyVersion(scalaDependency: ScalaDependency,
                                version: String) = {
      val newScalaDependency = scalaDependency.copy(version = version)
      copyAndSave(inputs = inputs.copy(
        libraries = (inputs.libraries - scalaDependency) + newScalaDependency))
    }

    def resetOutputs =
      copyAndSave(outputs = Outputs.default,
                  consoleIsOpen = false,
                  consoleHasUserOutput = false)

    def setRuntimeError(runtimeError: Option[RuntimeError]) =
      if (runtimeError.isEmpty) this
      else copyAndSave(outputs = outputs.copy(runtimeError = runtimeError))

    def addProgress(progress: PasteProgress) = {
      val state =
        addOutputs(progress.compilationInfos, progress.instrumentations)
          .log(progress.userOutput)
          .log(progress.sbtOutput)
          .setRunning(!progress.done)
          .setRuntimeError(progress.runtimeError)

      if (!progress.userOutput.isEmpty) state.setUserOutput
      else state
    }

    def setProgresses(progresses: List[PasteProgress]) = {
      progresses.foldLeft(this) {
        case (state, progress) => state.addProgress(progress)
      }
    }

    def addOutputs(compilationInfos: List[api.Problem],
                   instrumentations: List[api.Instrumentation]) =
      copyAndSave(outputs = outputs.copy(
        compilationInfos = outputs.compilationInfos ++ compilationInfos.toSet,
        instrumentations = outputs.instrumentations ++ instrumentations.toSet
      ))
  }

  class Backend(scope: BackendScope[Props, State]) {
    def codeChange(newCode: String) =
      scope.modState(_.setCode(newCode))

    def sbtConfigChange(newConfig: String) =
      scope.modState(_.setSbtConfigExtra(newConfig))

    private def connectEventSource(id: Int) = CallbackTo[EventSource] {
      val direct = scope.accessDirect

      val eventSource = new EventSource(s"/progress-sse/$id")

      def onopen(e: Event): Unit = {
        direct.modState(_.log("Connected.\n"))
      }
      def onmessage(e: MessageEvent): Unit = {
        val progress = uread[PasteProgress](e.data.toString)
        direct.modState(_.addProgress(progress))
        if(progress.done){
          eventSource.close()
        }
      }
      def onerror(e: Event): Unit = {
        if(e.eventPhase == EventSource.CLOSED) {
          eventSource.close()
        } else {
          direct.modState(_.log(s"Error: ${e.toString}"))
        }
      }

      eventSource.onopen = onopen _
      eventSource.onmessage = onmessage _
      eventSource.onerror = onerror _
      eventSource
    }

    private def connectWebSocket(id: Int) = CallbackTo[WebSocket] {
      val direct = scope.accessDirect

      def onopen(e: Event): Unit = direct.modState(_.log("Connected.\n"))
      def onmessage(e: MessageEvent): Unit = {
        val progress = uread[PasteProgress](e.data.toString)
        direct.modState(_.addProgress(progress))
      }
      def onerror(e: ErrorEvent): Unit =
        direct.modState(_.log(s"Error: ${e.message}"))
      def onclose(e: CloseEvent): Unit =
        direct.modState(
          _.copy(websocket = None, running = false)
            .log(s"Closed: ${e.reason}\n"))

      val protocol = if (window.location.protocol == "https:") "wss" else "ws"
      val uri = s"$protocol://${window.location.host}/progress-websocket/$id"
      val socket = new WebSocket(uri)

      socket.onopen = onopen _
      socket.onclose = onclose _
      socket.onmessage = onmessage _
      socket.onerror = onerror _
      socket
    }

    def clear(e: ReactEventI): Callback = clear()
    def clear(): Callback = scope.modState(_.resetOutputs)

    def setView(newView: View): Callback = 
      scope.modState(_.setView(newView))

    def setView2(newView: View)(e: ReactEventI): Callback = 
      setView(newView)
      
    def setTarget2(target: ScalaTarget)(e: ReactEventI): Callback =
      setTarget(target)

    def setTarget(target: ScalaTarget): Callback =
      scope.modState(_.setTarget(target))

    def addScalaDependency(scalaDependency: ScalaDependency): Callback =
      scope.modState(_.addScalaDependency(scalaDependency))

    def removeScalaDependency(scalaDependency: ScalaDependency): Callback =
      scope.modState(_.removeScalaDependency(scalaDependency))

    def changeDependencyVersion(scalaDependency: ScalaDependency,
                                version: String): Callback =
      scope.modState(_.changeDependencyVersion(scalaDependency, version))

    def toggleTheme(e: ReactEventI): Callback = toggleTheme()
    def toggleTheme(): Callback = scope.modState(_.toggleTheme)

    def toggleConsole(): Callback = scope.modState(_.toggleConsole)
    def toggleConsole(e: ReactEventI): Callback = toggleConsole()

    def toggleScriptMode(): Callback =
      scope.modState(_.toggleScriptMode)
    def toggleScriptMode(e: ReactEventI): Callback =
      toggleScriptMode()

    def run(e: ReactEventI): Callback = run()
    def run(): Callback = {
      scope.state.flatMap(s =>
        Callback.future(ApiClient[Api].run(s.inputs).call().map {
          case Ressource(id) =>
            connectEventSource(id).attemptTry.flatMap {
              case Success(eventSource) => {
                scope.modState(
                  _.resetOutputs
                    .setRunning(true)
                    .copy(eventSource = Some(eventSource))
                    .log("Connecting...\n")
                )
              }
              case Failure(errorEventSource) =>
                console.log("Failed to connect to event source: " + errorEventSource.toString)

                connectWebSocket(id).attemptTry.flatMap {
                  case Success(websocket) => {
                    scope.modState(
                      _.resetOutputs
                        .setRunning(true)
                        .copy(websocket = Some(websocket))
                        .log("Connecting...\n")
                    )     
                  }
                  case Failure(errorWebSocket) =>
                    scope.modState(
                      _.resetOutputs
                       .log(errorEventSource.toString)
                       .log(errorWebSocket.toString)
                       .setRunning(false)
                    )
                }
            }
        }))
    }
    def save(e: ReactEventI): Callback = save()
    def save(): Callback = {
      scope.state.flatMap(s =>
        Callback.future(ApiClient[Api].save(s.inputs).call().map {
          case Ressource(id) =>
            scope.props.flatMap(props =>
              props.router.map(_.set(Snippet(id))).getOrElse(Callback(())))
        }))
    }

    def start(props: Props): Callback = {
      console.log("== Welcome to Scastie ==")

      def loadSnippet(id: Int): Callback = {
        Callback.future(
          ApiClient[Api]
            .fetch(id)
            .call()
            .map(result =>
              result match {
                case Some(FetchResult(inputs, progresses)) => {
                  scope.modState(
                    _.setInputs(inputs).setProgresses(progresses)
                  )
                }
                case _ =>
                  scope.modState(_.setCode(s"//paste $id not found"))
            })
        )
      }

      props.embedded match {
        case None => {
          props.snippet match {
            case Some(Snippet(id)) => loadSnippet(id)
            case None => {
              LocalStorage.load
                .map(state => scope.modState(_ => state.setRunning(false)))
                .getOrElse(Callback(()))
            }
          }
        }
        case Some(embededOptions) => {
          embededOptions match {
            case EmbededOptions(Some(id), _) => loadSnippet(id)
            case EmbededOptions(_, Some(inputs)) =>
              scope.modState(_.setInputs(inputs))
            case _ => Callback(())
          }
        }
      }

    }

    def formatCode(e: ReactEventI): Callback = formatCode()
    def formatCode(): Callback =
      scope.state.flatMap(
        state =>
          Callback.future(
            ApiClient[Api]
              .format(
                FormatRequest(state.inputs.code, state.inputs.scriptMode))
              .call()
              .map {
                case FormatResponse(Right(formattedCode)) =>
                  scope.modState { s =>
                    // avoid overriding user's code if he/she types while it's formatting
                    if (s.inputs.code == state.inputs.code)
                      s.resetOutputs.setCode(formattedCode)
                    else s
                  }
                case FormatResponse(Left(fullStackTrace)) =>
                  scope.modState(_.resetOutputs.setRuntimeError(
                    Some(RuntimeError(message = "Formatting Failed", line = None, fullStack = fullStackTrace))
                  ))
              }
        ))
  }

  val component =
    ReactComponentB[Props]("App")
      .initialState(State.default)
      .backend(new Backend(_))
      .renderPS {
        case (scope, props, state) => {
          val theme =
            if (state.isDarkTheme) "dark"
            else "light"

          val sideBar =
            if (!props.isEmbedded) TagMod(SideBar(state, scope.backend))
            else EmptyTag

          val appClass =
            if (!props.isEmbedded) "app"
            else "app embedded"

          div(`class` := appClass)(
            sideBar,
            MainPannel(state, scope.backend, props.isEmbedded)
          )
        }
      }
      .componentWillMount(s => s.backend.start(s.props))
      .build

  def apply(props: Props) = component(props)
}