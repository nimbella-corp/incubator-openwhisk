/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.containerpool.logging

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Post
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.FormData
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.OverflowStrategy
import akka.stream.QueueOfferResult
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

import com.typesafe.sslconfig.akka.AkkaSSLConfig

import pureconfig._

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import spray.json._

import org.apache.openwhisk.common.AkkaLogging
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.entity.ActivationLogs
import org.apache.openwhisk.core.entity.WhiskActivation
import org.apache.openwhisk.core.database.UserContext

case class SplunkLogStoreConfig(host: String,
                                port: Int,
                                username: String,
                                password: String,
                                index: String,
                                logTimestampField: String,
                                logStreamField: String,
                                logMessageField: String,
                                activationIdField: String,
                                queryConstraints: String,
                                queryTimestampOffsetSeconds: Int,
                                disableSNI: Boolean)
case class SplunkResponse(results: Vector[JsObject])
object SplunkResponseJsonProtocol extends DefaultJsonProtocol {
  implicit val orderFormat = jsonFormat1(SplunkResponse)
}

/**
 * A Splunk based impl of LogDriverLogStore. Logs are routed to splunk via docker log driver, and retrieved via Splunk REST API
 *
 * @param actorSystem
 * @param httpFlow Optional Flow to use for HttpRequest handling (to enable stream based tests)
 */
class SplunkLogStore(
  actorSystem: ActorSystem,
  httpFlow: Option[Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), Any]] = None,
  splunkConfig: SplunkLogStoreConfig = loadConfigOrThrow[SplunkLogStoreConfig](ConfigKeys.splunk))
    extends LogDriverLogStore(actorSystem) {
  implicit val as = actorSystem
  implicit val ec = as.dispatcher
  implicit val materializer = ActorMaterializer()
  private val logging = new AkkaLogging(actorSystem.log)

  private val splunkApi = Path / "services" / "search" / "jobs" //see http://docs.splunk.com/Documentation/Splunk/6.6.3/RESTREF/RESTsearch#search.2Fjobs

  import SplunkResponseJsonProtocol._

  val maxPendingRequests = 500

  val defaultHttpFlow = Http().cachedHostConnectionPoolHttps[Promise[HttpResponse]](
    host = splunkConfig.host,
    port = splunkConfig.port,
    connectionContext =
      if (splunkConfig.disableSNI)
        Http().createClientHttpsContext(AkkaSSLConfig().mapSettings(s => s.withLoose(s.loose.withDisableSNI(true))))
      else Http().defaultClientHttpsContext)

  override def fetchLogs(activation: WhiskActivation, context: UserContext): Future[ActivationLogs] = {

    //example curl request:
    //    curl -u  username:password -k https://splunkhost:port/services/search/jobs -d exec_mode=oneshot -d output_mode=json -d "search=search index=\"someindex\" | spath=log_message | search activation_id=a930e5ae4ad4455c8f2505d665aad282 |  table log_message" -d "earliest_time=2017-08-29T12:00:00" -d "latest_time=2017-10-29T12:00:00"
    //example response:
    //    {"preview":false,"init_offset":0,"messages":[],"fields":[{"name":"log_message"}],"results":[{"log_message":"some log message"}], "highlighted":{}}
    //note: splunk returns results in reverse-chronological order, therefore we include "| reverse" to cause results to arrive in chronological order
    val search =
      s"""search index="${splunkConfig.index}"| spath ${splunkConfig.logMessageField}| search ${splunkConfig.queryConstraints} ${splunkConfig.activationIdField}=${activation.activationId.toString}| table ${splunkConfig.logTimestampField}, ${splunkConfig.logStreamField}, ${splunkConfig.logMessageField}| reverse"""

    val entity = FormData(
      Map(
        "exec_mode" -> "oneshot",
        "search" -> search,
        "output_mode" -> "json",
        "earliest_time" -> activation.start
          .minusSeconds(splunkConfig.queryTimestampOffsetSeconds)
          .toString, //assume that activation start/end are UTC zone, and splunk events are the same
        "latest_time" -> activation.end
          .plusSeconds(splunkConfig.queryTimestampOffsetSeconds) //add 5s to avoid a timerange of 0 on short-lived activations
          .toString)).toEntity

    logging.debug(this, "sending request")
    queueRequest(
      Post(Uri(path = splunkApi))
        .withEntity(entity)
        .withHeaders(List(Authorization(BasicHttpCredentials(splunkConfig.username, splunkConfig.password)))))
      .flatMap(response => {
        logging.debug(this, s"splunk API response ${response}")
        Unmarshal(response.entity)
          .to[SplunkResponse]
          .map(
            r =>
              ActivationLogs(
                r.results
                  .map(js => Try(toLogLine(js)))
                  .map {
                    case Success(s) => s
                    case Failure(t) =>
                      logging.debug(
                        this,
                        s"The log message might have been too large " +
                          s"for '${splunkConfig.index}' Splunk index and can't be retrieved, ${t.getMessage}")
                      s"The log message can't be retrieved, ${t.getMessage}"
                  }))
      })
  }

  private def toLogLine(l: JsObject) = //format same as org.apache.openwhisk.core.containerpool.logging.LogLine.toFormattedString
    f"${l.fields(splunkConfig.logTimestampField).convertTo[String]}%-30s ${l
      .fields(splunkConfig.logStreamField)
      .convertTo[String]}: ${l.fields(splunkConfig.logMessageField).convertTo[String].trim}"

  //based on http://doc.akka.io/docs/akka-http/10.0.6/scala/http/client-side/host-level.html
  val queue =
    Source
      .queue[(HttpRequest, Promise[HttpResponse])](maxPendingRequests, OverflowStrategy.dropNew)
      .via(httpFlow.getOrElse(defaultHttpFlow))
      .toMat(Sink.foreach({
        case ((Success(resp), p)) => p.success(resp)
        case ((Failure(e), p))    => p.failure(e)
      }))(Keep.left)
      .run()

  def queueRequest(request: HttpRequest): Future[HttpResponse] = {
    val responsePromise = Promise[HttpResponse]()
    queue.offer(request -> responsePromise).flatMap {
      case QueueOfferResult.Enqueued => responsePromise.future
      case QueueOfferResult.Dropped =>
        Future.failed(new RuntimeException("Splunk API Client Queue overflowed. Try again later."))
      case QueueOfferResult.Failure(ex) => Future.failed(ex)
      case QueueOfferResult.QueueClosed =>
        Future.failed(
          new RuntimeException(
            "Splunk API Client Queue was closed (pool shut down) while running the request. Try again later."))
    }
  }
}

object SplunkLogStoreProvider extends LogStoreProvider {
  override def instance(actorSystem: ActorSystem) = new SplunkLogStore(actorSystem)
}
