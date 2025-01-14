package app
import io.undertow.Undertow

import utest._

object ExampleTests extends TestSuite{
  def withServer[T](example: cask.main.Main)(f: String => T): T = {
    val server = Undertow.builder
      .addHttpListener(8080, "localhost")
      .setHandler(example.defaultHandler)
      .build
    server.start()
    val res =
      try f("http://localhost:8080")
      finally server.stop()
    res
  }

  val tests = Tests{

    test("StaticFiles") - withServer(StaticFiles){ host =>
      requests.get(s"$host/static/file/example.txt").text() ==>
        "the quick brown fox jumps over the lazy dog"

      requests.get(s"$host/static/resource/example.txt").text() ==>
        "the quick brown fox jumps over the lazy dog"

      requests.get(s"$host/static/resource2/cask/example.txt").text() ==>
        "the quick brown fox jumps over the lazy dog"

      requests.get(s"$host/static/file/../../../build.sc").statusCode ==> 404
    }

  }
}
