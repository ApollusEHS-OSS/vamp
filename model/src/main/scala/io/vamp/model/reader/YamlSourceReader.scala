package io.vamp.model.reader

import io.vamp.model.notification.{MissingPathValueError, ModelNotificationProvider, UnexpectedInnerElementError, UnexpectedTypeError, ParserError}
import io.vamp.common.{RestrictedAny, RestrictedBoolean, RestrictedDouble, RestrictedInt, RestrictedList, RestrictedMap, RestrictedString, RootAnyMap}

import scala.collection.immutable.::
import scala.collection.mutable
import scala.language.{implicitConversions, postfixOps}
import scala.reflect._
import scala.util.{Failure, Success, Try}

object YamlSourceReader {
  type YamlPath = List[String]
  type YamlList = List[YamlSourceReader]

  implicit def string2Path(path: String): YamlPath = path.split('/').toList

  def apply(entries: (String, Any)*): YamlSourceReader = apply(Map(entries: _*))

  def apply(map: collection.Map[String, Any] = Map[String, Any]()): YamlSourceReader = new YamlSourceReader(map)
}

class YamlSourceReader(map: collection.Map[String, Any]) extends ModelNotificationProvider {

  import YamlSourceReader._

  private val source = {
    def transform(value: Any): Any = value match {
      case value: List[_]              ⇒ value.map(transform)
      case value: collection.Map[_, _] ⇒ YamlSourceReader(value.asInstanceOf[collection.Map[String, Any]])
      case any                         ⇒ any
    }

    new mutable.LinkedHashMap[String, Any]() ++= map.map { case (key, value) ⇒ key → transform(value) }
  }

  private val _consumed = new mutable.LinkedHashMap[String, Any]()

  def find[V <: Any: ClassTag](path: YamlPath): Option[V] = find[V](this, path)

  def findSilent[V <: Any: ClassTag](path: YamlPath): Option[V] = find[V](this, path, silent = true)

  def get[V <: Any: ClassTag](path: YamlPath): V = find[V](this, path) match {
    case None    ⇒ throwException(MissingPathValueError(path mkString "/"))
    case Some(v) ⇒ v
  }

  def set(path: YamlPath, value: Option[Any]): Option[Any] = set(this, path, value)

  def push(yaml: YamlSourceReader) = yaml.source.foreach { case (key, value) ⇒ source.put(key, value) }

  def pull(accept: (String) ⇒ Boolean = (String) ⇒ true): Map[String, Any] = source.filterKeys(accept).map(consume).toMap

  def flatten(accept: (String) ⇒ Boolean = (String) ⇒ true): Map[String, Any] = {
    source.filterKeys(accept).map(consume).map {
      case (key, value: YamlSourceReader) ⇒ key → value.flatten((String) ⇒ true)
      case (key, value: List[_]) ⇒
        key → value.map {
          case yaml: YamlSourceReader ⇒ yaml.flatten((String) ⇒ true)
          case any                    ⇒ any
        }
      case (key, value) ⇒ key → value
    } toMap
  }

  def flattenToRootAnyMap(accept: (String) ⇒ Boolean = (String) ⇒ true): RootAnyMap = RootAnyMap(source.filterKeys(accept).map(kvPair => consume((kvPair._1, kvPair._2))).map {
    kvPair => (kvPair._1, extractRestrictedAnyType(kvPair._2))
  } toMap)

  def extractRestrictedAnyType(any: Any): RestrictedAny = {
    any match {
      case value: YamlSourceReader => RestrictedMap(value.source.map(value.consume).map {
        kvPair => (kvPair._1, extractRestrictedAnyType(kvPair._2))
      }.toMap)
      case value: List[_] => RestrictedList(value.map(extractRestrictedAnyType))
      case value: String => RestrictedString(value)
      case value: Int => RestrictedInt(value)
      case value: Double => RestrictedDouble(value)
      case value: Boolean => RestrictedBoolean(value)
      case _ => throwException(ParserError(s"Cannot parse ${any} as an element of type String, Int, Double, String, Boolean, List or Map"))
    }
  }

  def flattenNotConsumed(path: String = ""): Map[String, Any] =
    notConsumed
      .map(consume)
      .flatMap {
        case (key, value: YamlSourceReader) ⇒ value.flattenNotConsumed(key)
        case (key, map: Map[_, _])          ⇒ YamlSourceReader(map.asInstanceOf[Map[String, Any]]).flattenNotConsumed(key)
        case (key, ls: List[_]) ⇒
          val addPath = if (path.isEmpty) "" else s"$path."
          ls.map(s"$addPath$key" → _).toMap
        case (key, value) ⇒
          val addPath = if (path.isEmpty) "" else s"$path."
          Map(s"$addPath$key" → value)
      }

  def size: Int = source.size

  def consumed: Map[String, _] = _consumed.flatMap({
    case (key, value: YamlSourceReader) ⇒
      val map = value.consumed

      if (map.isEmpty) Nil else (key → map) :: Nil

    case (key, value: List[_]) ⇒
      val list = value.flatMap {
        case yaml: YamlSourceReader ⇒
          val c = yaml.consumed
          if (c.isEmpty) Nil else c :: Nil
        case any ⇒ any :: Nil
      }

      if (list.isEmpty) Nil else (key → list) :: Nil

    case (key, value) ⇒ (key → value) :: Nil
  }).toMap

  def notConsumed: Map[String, _] = notConsumed(source, _consumed)

  private def find[V <: Any: ClassTag](target: YamlSourceReader, path: YamlPath, silent: Boolean = false): Option[V] =
    path match {
      case last :: Nil ⇒
        Try {
          target.source.get(last) match {
            case None ⇒ None
            case Some(null) ⇒ None
            case Some(value: V) ⇒ Some(value.asInstanceOf[V])
            // if V == Double, conversion from Int to Double if Double is expected and Int provided.
            case Some(value: Int) if classTag[V].runtimeClass == classOf[Double] ⇒ Some(value.toDouble.asInstanceOf[V])
            // if V == String
            case Some(value) if classTag[V].runtimeClass == classOf[String] ⇒ Some(value.toString.asInstanceOf[V])
            // if V == Map
            case Some(value: YamlSourceReader) if classTag[V].runtimeClass == classOf[Map[_, _]] ⇒ Some(value.pull().asInstanceOf[V])
            // if V == List
            case Some(value: List[_]) if classTag[V].runtimeClass == classOf[List[_]] ⇒ Some(value.asInstanceOf[V])
            case Some(value) ⇒
              Some(UnitValue
                .of[V](value)
                .getOrElse {
                  if (silent) throw SilentException
                  else throwException(UnexpectedTypeError(last, classTag[V].runtimeClass, value.getClass))
                })
          }
        } match {
          case Success(value) ⇒
            target._consumed.put(last, value.getOrElse(None))
            value
          case Failure(e) ⇒ throw e
        }

      case head :: tail ⇒ target.source.get(head).filter(_ != null).flatMap {
        case yaml: YamlSourceReader ⇒ Try {
          find[V](yaml, tail)
        } match {
          case Success(value) ⇒
            if (value.isDefined) _consumed.put(head, yaml)
            value
          case Failure(e) ⇒ throw e
        }
        case failure ⇒ throwException(UnexpectedInnerElementError(head, failure.getClass))
      }

      case Nil ⇒ None
    }

  private def set(target: YamlSourceReader, path: YamlPath, value: Option[Any]): Option[Any] = {

    def insert(key: String, path: List[String], value: Option[Any]) = {
      val next = YamlSourceReader()
      target.source.put(key, next)
      set(next, path, value)
    }

    path match {
      case Nil ⇒ None
      case last :: Nil ⇒
        value match {
          case None    ⇒ target.source.remove(last)
          case Some(v) ⇒ target.source.put(last, v)
        }
      case head :: tail ⇒
        target.source.get(head) match {
          case None                         ⇒ insert(head, tail, value)
          case Some(yaml: YamlSourceReader) ⇒ set(yaml, tail, value)
          case Some(_)                      ⇒ insert(head, tail, value)
        }
    }
  }

  private def consume(entry: (String, Any)): (String, Any) = {
    _consumed.put(entry._1, entry._2)
    entry
  }

  private def notConsumed(all: collection.Map[String, _], cons: collection.Map[String, _]): Map[String, _] = {
    all.flatMap {
      case (key, value: YamlSourceReader) if cons.get(key).isDefined ⇒
        cons.get(key) match {
          case Some(yaml: YamlSourceReader) ⇒
            val map = yaml.notConsumed
            if (map.isEmpty) Nil else (key → yaml.notConsumed) :: Nil
          case _ ⇒ if (value.source.isEmpty) Nil else (key → value) :: Nil
        }

      case (key, value: List[_]) if cons.get(key).nonEmpty ⇒
        val list = value.flatMap {
          case y: YamlSourceReader ⇒
            val nc = y.notConsumed
            if (nc.isEmpty) Nil else nc :: Nil

          case _ ⇒ Nil
        }

        if (list.isEmpty) Nil else (key → list) :: Nil

      case (key, value) if cons.get(key).isEmpty ⇒
        value match {
          case y: YamlSourceReader ⇒ (key → y.notConsumed) :: Nil
          case l: List[_] ⇒ (key →
            l.flatMap {
              case yaml: YamlSourceReader ⇒
                val c = yaml.consumed
                if (c.isEmpty) Nil else c :: Nil
              case any ⇒ any :: Nil
            }) :: Nil
          case _ ⇒ (key → value) :: Nil
        }
      case _ ⇒ Nil
    } toMap
  }
}
