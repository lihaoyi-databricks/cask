package cask.router

import cask.internal.Conversion
import cask.model.{Request, Response}
import cask.router.{ArgReader, EntryPoint, Result}

/**
  * A [[Decorator]] allows you to annotate a function to wrap it, via
  * `wrapFunction`. You can use this to perform additional validation before or
  * after the function runs, provide an additional parameter list of params,
  * open/commit/rollback database transactions before/after the function runs,
  * or even retrying the wrapped function if it fails.
  *
  * Calls to the wrapped function are done on the `delegate` parameter passed
  * to `wrapFunction`, which takes a `Map` representing any additional argument
  * lists (if any).
  */
trait Decorator[InnerReturned, Input]{
  final type InputTypeAlias = Input
  type InputParser[T] <: ArgReader[Input, T, Request]
  final type Delegate = Map[String, Input] => Result[InnerReturned]
  type OuterReturned <: Result[Any]
  def wrapFunction(ctx: Request, delegate: Delegate): OuterReturned
  def getParamParser[T](implicit p: InputParser[T]) = p
}
object Decorator{
  /**
   * A stack of [[Decorator]]s is invoked recursively: each decorator's `wrapFunction`
   * is invoked around the invocation of all inner decorators, with the inner-most
   * decorator finally invoking the route's [[EntryPoint.invoke]] function.
   *
   * Each decorator (and the final `Endpoint`) contributes a dictionary of name-value
   * bindings, which are eventually all passed to [[EntryPoint.invoke]]. Each decorator's
   * dictionary corresponds to a different argument list on [[EntryPoint.invoke]]. The
   * bindings passed from the router are aggregated with those from the `EndPoint` and
   * used as the first argument list.
   */
  def invoke[T](ctx: Request,
                endpoint: Endpoint[_, _],
                entryPoint: EntryPoint[T, _],
                routes: T,
                routeBindings: Map[String, String],
                remainingDecorators: List[RawDecorator],
                bindings: List[Map[String, Any]]): Result[Any] = try {
    remainingDecorators match {
      case head :: rest =>
        head.wrapFunction(
          ctx,
          args => invoke(ctx, endpoint, entryPoint, routes, routeBindings, rest, args :: bindings)
            .asInstanceOf[Result[cask.model.Response.Raw]]
        )

      case Nil =>
        endpoint.wrapFunction(ctx, { (endpointBindings: Map[String, Any]) =>
          val mergedEndpointBindings = endpointBindings ++ routeBindings.mapValues(endpoint.wrapPathSegment)
          val finalBindings = mergedEndpointBindings :: bindings

          entryPoint
            .asInstanceOf[EntryPoint[T, cask.model.Request]]
            .invoke(routes, ctx, finalBindings)
            .asInstanceOf[Result[Nothing]]
        })
    }
    // Make sure we wrap any exceptions that bubble up from decorator
    // bodies, so outer decorators do not need to worry about their
    // delegate throwing on them
  }catch{case e: Throwable => Result.Error.Exception(e) }
}

/**
  * A [[RawDecorator]] is a decorator that operates on the raw request and
  * response stream, before and after the primary [[Endpoint]] does it's job.
  */
trait RawDecorator extends Decorator[Response.Raw, Any]{
  type OuterReturned = Result[Response.Raw]
  type InputParser[T] = NoOpParser[Any, T]
}


/**
  * An [[HttpEndpoint]] that may return something else than a HTTP response, e.g.
  * a websocket endpoint which may instead return a websocket event handler
  */
trait Endpoint[InnerReturned, Input] extends Decorator[InnerReturned, Input]{
  /**
    * What is the path that this particular endpoint matches?
    */
  val path: String
  /**
    * Which HTTP methods does this endpoint support? POST? GET? PUT? Or some
    * combination of those?
    */
  val methods: Seq[String]

  /**
    * Whether or not this endpoint allows matching on sub-paths: does
    * `@endpoint("/foo")` capture the path "/foo/bar/baz"? Useful to e.g. have
    * an endpoint match URLs with paths in a filesystem (real or virtual) to
    * serve files
    */
  def subpath: Boolean = false

  def convertToResultType[T](t: T)
                            (implicit f: Conversion[T, InnerReturned]): InnerReturned = {
    f.f(t)
  }

  /**
    * [[HttpEndpoint]]s are unique among decorators in that they alone can bind
    * path segments to parameters, e.g. binding `/hello/:world` to `(world: Int)`.
    * In order to do so, we need to box up the path segment strings into an
    * [[Input]] so they can later be parsed by [[getParamParser]] into an
    * instance of the appropriate type.
    */
  def wrapPathSegment(s: String): Input

}

/**
  * Annotates a Cask endpoint that returns a HTTP [[Response]]; similar to a
  * [[RawDecorator]] but with additional metadata and capabilities.
  */
trait HttpEndpoint[InnerReturned, Input] extends Endpoint[InnerReturned, Input] {
  type OuterReturned = Result[Response.Raw]
}


class NoOpParser[Input, T] extends ArgReader[Input, T, Request] {
  def arity = 1

  def read(ctx: Request, label: String, input: Input) = input.asInstanceOf[T]
}
object NoOpParser{
  implicit def instance[Input, T] = new NoOpParser[Input, T]
  implicit def instanceAny[T] = new NoOpParser[Any, T]
}