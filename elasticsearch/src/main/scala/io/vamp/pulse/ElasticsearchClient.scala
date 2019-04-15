package io.vamp.pulse

import akka.actor.ActorSystem
import akka.util.Timeout
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.http.{ ElasticClient, ElasticDsl }
import com.sksamuel.elastic4s.searches.aggs.Aggregation
import com.sksamuel.elastic4s.searches.queries.Query
import com.sksamuel.elastic4s.searches.sort.Sort
import io.vamp.common.Namespace
import io.vamp.pulse.ElasticsearchClient._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

object ElasticsearchClient {

  case class ElasticsearchIndexResponse(_index: String, _type: String, _id: String)

  case class ElasticsearchSearchResponse(hits: ElasticsearchSearchHits)

  case class ElasticsearchSearchHits(total: Long, hits: List[ElasticsearchHit])

  case class ElasticsearchHit(_index: String, _type: String, _id: String, _source: Map[String, Any] = Map())

  case class ElasticsearchGetResponse(_index: String, _type: String, _id: String, found: Boolean, _source: Map[String, Any] = Map())

  case class ElasticsearchCountResponse(count: Long)

  case class ElasticsearchAggregationResponse(aggregations: ElasticsearchAggregations)

  case class ElasticsearchAggregations(aggregation: ElasticsearchAggregationValue)

  case class ElasticsearchAggregationValue(value: Double = 0)

}

class ElasticsearchClient(elasticClient: ElasticClient)(implicit val timeout: Timeout, val namespace: Namespace, val system: ActorSystem) {
  implicit val executionContext: ExecutionContext = system.dispatcher

  def health(): Future[String] = {
    val healthResponseFuture = elasticClient.execute(ElasticDsl.clusterHealth())
    healthResponseFuture.map(response ⇒ response.body.getOrElse(s"Could not get health results: ${response.error}"))
  }

  def version(): Future[Option[String]] = {
    val catMasterResponseFuture = elasticClient.execute(ElasticDsl.catMaster())
    val masterIdFuture = catMasterResponseFuture.map(response ⇒
      for {
        responseOption ← response.toOption
        masterIdOption ← Option(responseOption.id)
      } yield masterIdOption)

    val nodesInfoResponseFuture = masterIdFuture.flatMap(masterId ⇒ elasticClient.execute(ElasticDsl.nodeInfo(masterId)))
    nodesInfoResponseFuture.map(response ⇒
      for {
        responseOption ← response.toOption
        nodeInfoOption ← responseOption.nodes.headOption
        versionOption ← Option(nodeInfoOption._2.version)
      } yield versionOption)
  }

  def exists(index: String, `type`: String, id: String): Future[Boolean] = {
    val documentResponseFuture = elasticClient.execute {
      ElasticDsl.get(index, `type`, id)
    }
    documentResponseFuture.map(response ⇒ {
      response.toOption match {
        case Some(r) ⇒ r.exists
        case _       ⇒ false
      }
    })
  }

  def index(index: String, `type`: String, jsonDoc: String): Future[ElasticsearchIndexResponse] = {
    val indexResponseFuture = elasticClient.execute {
      ElasticDsl.indexInto(index, `type`).doc(jsonDoc)
    }
    indexResponseFuture.flatMap(response ⇒ {
      response.toOption match {
        case Some(r) ⇒ Future(ElasticsearchIndexResponse(index, `type`, r.id))
        case _       ⇒ Future.failed(new RuntimeException(s"Could not get index results: ${response.error}"))
      }
    })
  }

  def search(index: String, query: Query, from: Int, size: Int, sort: Sort): Future[ElasticsearchSearchResponse] = {
    val searchResponseFuture = elasticClient.execute {
      ElasticDsl
        .search(index)
        .query(query)
        .from(from)
        .size(size)
        .sortBy(sort)
    }
    searchResponseFuture.flatMap(response ⇒ {
      response.toOption match {
        case Some(r) ⇒ {
          val hits = r.hits.hits.map(hit ⇒ ElasticsearchHit(hit.index, hit.`type`, hit.id, hit.sourceAsMap)).toList
          Future(ElasticsearchSearchResponse(ElasticsearchSearchHits(r.hits.total, hits)))
        }
        case _ ⇒ Future.failed(new RuntimeException(s"Could not get search results: ${response.error}"))
      }
    })
  }

  def count(index: String, query: Query): Future[ElasticsearchCountResponse] = {
    val countResponseFuture = elasticClient.execute {
      ElasticDsl.count(index).filter(query)
    }

    countResponseFuture.flatMap(response ⇒ {
      response.toOption match {
        case Some(r) ⇒ Future(ElasticsearchCountResponse(r.count))
        case _       ⇒ Future.failed(new RuntimeException(s"Could not get count results: ${response.error}"))
      }
    })
  }

  def aggregate(index: String, query: Query, aggregation: Aggregation): Future[ElasticsearchAggregationResponse] = {
    val aggregateResponseFuture = elasticClient.execute {
      ElasticDsl.search(index).query(query).aggregations(aggregation)
    }
    aggregateResponseFuture.flatMap(response ⇒ {
      response.toOption match {
        case Some(r) ⇒ {
          val aggregationValue = for {
            agg ← r.aggs.data.get(aggregation.name)
            aggMap ← agg match {
              case valueMap: Map[Any, Any] ⇒ Some(valueMap)
              case _                       ⇒ None
            }
            value ← aggMap.get("value")
            doubleValue ← Try(value.toString.toDouble).toOption
          } yield doubleValue
          aggregationValue match {
            case Some(value) ⇒ Future(ElasticsearchAggregationResponse(ElasticsearchAggregations(ElasticsearchAggregationValue(value.toString.toDouble))))
            case _           ⇒ Future.failed(new RuntimeException(s"Aggregation value not found: ${aggregation.name}"))
          }
        }
        case _ ⇒ Future.failed(new RuntimeException(s"Could not get aggregation results: ${response.error}"))
      }
    })
  }
}
