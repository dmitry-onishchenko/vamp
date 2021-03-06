package io.vamp.persistence

import io.vamp.common.Artifact
import io.vamp.common.akka.CommonActorLogging
import io.vamp.common.http.OffsetEnvelope
import io.vamp.common.notification.NotificationProvider

import scala.collection.mutable
import scala.language.postfixOps
import scala.reflect.ClassTag

trait PersistenceRepresentation extends PersistenceApi with AccessGuard {
  this: CommonActorLogging with NotificationProvider ⇒

  private val store: mutable.Map[String, mutable.Map[String, Artifact]] = new mutable.HashMap()

  protected def info(): Map[String, Any] = Map[String, Any](
    "status" → (if (validData) "valid" else "corrupted"),
    "artifacts" → (store.map {
      case (key, value) ⇒ key → value.values.size
    } toMap)
  )

  protected def all(`type`: String): List[Artifact] = store.get(`type`).map(_.values.toList).getOrElse(Nil)

  protected def all[T <: Artifact](kind: String, page: Int, perPage: Int, filter: T ⇒ Boolean): ArtifactResponseEnvelope = {
    log.debug(s"In memory representation: all [$kind] of $page per $perPage")
    val artifacts = all(kind).filter { artifact ⇒ filter(artifact.asInstanceOf[T]) }
    val total = artifacts.size
    val (p, pp) = OffsetEnvelope.normalize(page, perPage, ArtifactResponseEnvelope.maxPerPage)
    val (rp, rpp) = OffsetEnvelope.normalize(total, p, pp, ArtifactResponseEnvelope.maxPerPage)
    ArtifactResponseEnvelope(artifacts.slice((p - 1) * pp, p * pp), total, rp, rpp)
  }

  protected def get[T <: Artifact](name: String, kind: String): Option[T] = {
    log.debug(s"In memory representation: read [$kind] - $name}")
    store.get(kind).flatMap(_.get(name)).asInstanceOf[Option[T]]
  }

  protected def set[T <: Artifact](artifact: T, kind: String): T = {
    def put(map: mutable.Map[String, Artifact]) = {
      map.put(artifact.name, before(
        artifact
      ))
      after(set = true)(artifact)
    }

    log.debug(s"In memory representation: set [$kind] - ${artifact.name}")
    store.get(kind) match {
      case None ⇒
        val map = new mutable.HashMap[String, Artifact]()
        put(map)
        store.put(kind, map)
      case Some(map) ⇒ put(map)
    }
    artifact
  }

  protected def delete[T <: Artifact](name: String, kind: String): Option[T] = {
    log.debug(s"In memory representation: delete [$kind] - $name}")
    store.get(kind) flatMap { map ⇒
      val result = map.remove(name).map { artifact ⇒ after[T](set = false)(artifact.asInstanceOf[T]) }
      if (result.isEmpty) log.debug(s"Artifact not found for deletion: $kind: $name")
      result
    }
  }

  protected def find[A: ClassTag](p: A ⇒ Boolean, `type`: Class[_ <: Artifact]): Option[A] = {
    store.get(type2string(`type`)).flatMap {
      _.find {
        case (_, artifact: A) ⇒ p(artifact)
        case _                ⇒ false
      }
    } map (_._2.asInstanceOf[A])
  }

  protected def before[T <: Artifact](artifact: T): T = artifact

  protected def after[T <: Artifact](set: Boolean)(artifact: T): T = artifact
}
