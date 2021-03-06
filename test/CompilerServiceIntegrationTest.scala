import
  org.scalatestplus.play.{PlaySpec, OneAppPerSuite}

import
  play.api.{libs, mvc, test},
    libs.{ iteratee, json },
      iteratee.Iteratee,
      json.{ JsObject, Json, JsString },
    mvc.{ EssentialAction, Result },
    test.{ FakeRequest, FakeApplication, Helpers },
      Helpers.{ await, call, defaultAwaitTimeout, writeableOf_AnyContentAsFormUrlEncoded }

import
  scala.concurrent.Future

class CompilerServiceIntegrationTest extends PlaySpec with OneAppPerSuite {

  import CompilerServiceHelpers._
  import scala.concurrent.ExecutionContext.Implicits.global

  override implicit lazy val app: FakeApplication =
    FakeApplication(additionalConfiguration = Map(
      "play.akka.shutdown-timeout" -> "2s",
      "akka.log-dead-letters"      -> 0
    ))

  "CompilerService controller" must {
    Map(
      "wolf sheep"          -> wolfSheep,
      "custom turtle shape" -> breedProcedures,
      "custom link shape"   -> linkBreeds
    ).foreach {
      case (name, modelText) =>

        s"preserve $name model information" in {
          val (firstResult, firstResultBody) = await(makeRequest("POST", "/compile-nlogo", "model" -> modelText))
          firstResult.header.status mustEqual 200

          val fields = sanitizedJsonModel(firstResultBody, "turtleShapes", "linkShapes") - "model"

          val (secondResult, secondResultBody) = await(makeRequest("POST", "/compile-code", fields.toSeq: _*))
          secondResult.header.status mustEqual 200
          secondResultBody mustEqual firstResultBody
        }
    }
  }

  private def makeRequest(method: String, path: String, formBody: (String, String)*): Future[(Result, String)] = {
    val req = FakeRequest(method, path).withFormUrlEncodedBody(formBody: _*)
    val (_, handler) = app.requestHandler.handlerForRequest(req)
    call(handler.asInstanceOf[EssentialAction], req).flatMap[(Result, String)] { res =>
      res.body |>>> Iteratee.consume[Array[Byte]]().map(new String(_, "UTF-8")).map((res, _))
    }
  }

  private def sanitizedJsonModel(rawJson: String, modelVars: String*): Map[String, String] = {
    val jobject@JsObject(jsonFields) = Json.parse(rawJson)

    val JsString(modelJs) = (jobject \ "model" \ "result").get

    val fields = jsonFields.toMap.map {
      case ("widgets", JsString(s)) => ("widgets", Json.prettyPrint(Json.parse(s.replaceAll(":function\\(\\) \\{.*\\},\"", ":null,\""))))
      case (k,         JsString(s)) => (k,         s)
      case (k,         j)           => (k,         Json.prettyPrint(j))
    }

    val jsVarRegex = """(?m)^var (\w+) = (.*);$""".r

    val declaredVars = jsVarRegex.findAllMatchIn(modelJs).map {
      case jsVarRegex(varName, varValue) => varName -> varValue
    }

    fields ++ declaredVars.filter(t => modelVars.contains(t._1))
  }
}
