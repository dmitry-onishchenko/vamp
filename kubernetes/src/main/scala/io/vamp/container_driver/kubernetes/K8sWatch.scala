package io.vamp.container_driver.kubernetes

import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, Materializer }
import akka.stream.scaladsl.Source
import com.google.gson.reflect.TypeToken
import com.squareup.okhttp.Call
import com.typesafe.scalalogging.{ LazyLogging, Logger }
import io.kubernetes.client.models._
import io.kubernetes.client.util.Watch
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.language.postfixOps
import ExecutionContext.Implicits.global

case class WatchDefinition(kind: String, call: () ⇒ Call, watch: (Call) ⇒ Watch[AnyRef])

class K8sWatch(client: K8sClient)(implicit system: ActorSystem) extends LazyLogging {

  // Testing using ExecutionContext.Implicits.global instead of system.dispatcher
  // private implicit val ec: ExecutionContext = system.dispatcher

  implicit val materializer = ActorMaterializer()
  private var running = true

  private val retryDelay = 5 seconds
  private val initialDelay = 1 seconds

  logger.info(s"starting Kubernetes watch: ${client.config.url}")

  private val watchHandles = new mutable.HashMap[String, Call]()
  val futureWatches = Seq(
    WatchDefinition(
      K8sCache.jobs,
      () ⇒ client.batchV1Api.listJobForAllNamespacesCall(null, null, null, null, 0, true, null, null),
      (call: Call) ⇒ Watch.createWatch(client.batchV1Api.getApiClient, call, new TypeToken[Watch.Response[V1Job]]() {}.getType)
    ),

    WatchDefinition(
      K8sCache.pods,
      () ⇒ client.coreV1Api.listPodForAllNamespacesCall(null, null, null, null, 0, true, null, null),
      (call: Call) ⇒ Watch.createWatch(client.coreV1Api.getApiClient, call, new TypeToken[Watch.Response[V1Pod]]() {}.getType)
    ),

    WatchDefinition(
      K8sCache.services,
      () ⇒ client.coreV1Api.listServiceForAllNamespacesCall(null, null, null, null, 0, true, null, null),
      (call: Call) ⇒ Watch.createWatch(client.coreV1Api.getApiClient, call, new TypeToken[Watch.Response[V1Service]]() {}.getType)
    ),

    WatchDefinition(
      K8sCache.daemonSets,
      () ⇒ client.extensionsV1beta1Api.listDaemonSetForAllNamespacesCall(null, null, null, null, 0, true, null, null),
      (call: Call) ⇒ Watch.createWatch(client.extensionsV1beta1Api.getApiClient, call, new TypeToken[Watch.Response[V1beta1DaemonSet]]() {}.getType)
    ),

    WatchDefinition(
      K8sCache.deployments,
      () ⇒ client.extensionsV1beta1Api.listDeploymentForAllNamespacesCall(null, null, null, null, 0, true, null, null),
      (call: Call) ⇒ Watch.createWatch(client.extensionsV1beta1Api.getApiClient, call, new TypeToken[Watch.Response[ExtensionsV1beta1Deployment]]() {}.getType)
    ),

    WatchDefinition(
      K8sCache.replicaSets,
      () ⇒ client.extensionsV1beta1Api.listReplicaSetForAllNamespacesCall(null, null, null, null, 0, true, null, null),
      (call: Call) ⇒ Watch.createWatch(client.extensionsV1beta1Api.getApiClient, call, new TypeToken[Watch.Response[V1beta1ReplicaSet]]() {}.getType)
    )
  )

  val doneFuture = Source
    .fromIterator(() ⇒ futureWatches.iterator)
    .mapAsync(parallelism = 1)(wdef ⇒ watch(wdef.kind, wdef.call, wdef.watch))
    .runForeach { identity }

  def close(): Unit = {
    logger.info(s"closing Kubernetes watch: ${client.config.url}")
    running = false
    watchHandles.values.foreach(_.cancel())
  }

  private def watch(kind: String, call: () ⇒ Call, watch: (Call) ⇒ Watch[AnyRef]): Future[Unit] = Future {

    def stream(): Unit = {
      try {
        logger.info(s"watching [$kind]: ${client.config.url}")
        val c = call()
        watchHandles.put(kind, c)
        watch(c).iterator().asScala.foreach(handleEvent)
      }
      catch {
        case e: Exception ⇒
          if (running) {
            logger.error(s"ERROR: watch $kind: ${e.getMessage}")
            system.scheduler.scheduleOnce(retryDelay, () ⇒ stream())
          }
      }
    }

    stream()
    //    system.scheduler.scheduleOnce(initialDelay, () ⇒ stream())
  }

  private def handleEvent(event: Watch.Response[_]): Unit = {

    def invalidate(kind: String, name: String): Unit = client.caches.foreach(_.invalidate(kind, name))

    event.`object` match {
      case j: V1Job                       ⇒ invalidate(K8sCache.jobs, j.getMetadata.getName)
      case p: V1Pod                       ⇒ invalidate(K8sCache.pods, p.getMetadata.getName)
      case s: V1Service                   ⇒ invalidate(K8sCache.services, s.getMetadata.getName)
      case d: V1beta1DaemonSet            ⇒ invalidate(K8sCache.daemonSets, d.getMetadata.getName)
      case d: ExtensionsV1beta1Deployment ⇒ invalidate(K8sCache.deployments, d.getMetadata.getName)
      case r: V1beta1ReplicaSet           ⇒ invalidate(K8sCache.replicaSets, r.getMetadata.getName)
      case _                              ⇒
    }
  }
}
