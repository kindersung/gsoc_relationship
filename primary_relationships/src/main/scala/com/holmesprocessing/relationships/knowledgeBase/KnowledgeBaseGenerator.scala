package com.holmesprocessing.relationships.knowledgeBase

import com.holmesprocessing.relationships.models._
import com.holmesprocessing.relationships.knowledgeBase.HelperMethods._
import com.holmesprocessing.relationships.SparkConfig._

import com.datastax.spark.connector._
import com.datastax.driver.core.utils.UUIDs
import com.datastax.spark.connector.SomeColumns
import play.api.libs.json.Json

object KnowledgeBaseGenerator {


  def run(batch: List[String]) = {

    //intermediate RDD that acts as storage for everything
    var knowledge_base_rdd = sc.parallelize(Seq[Knowledge_Base]())

    //Input is a List[String] of hashes, the connector can query the table for each element in the batch in one go
    val results = sc.cassandraTable(keyspace,results_meta_by_sha256).where("sha256 in ?",batch).joinWithCassandraTable(keyspace,results_data_by_sha256).map(x=> (x._1.get[String]("sha256"), x._1.get[String]("service_name"), decompress(x._2.get[Array[Byte]]("results")))).cache()

    val peinfo_res = results.filter(x=> x._2 == "peinfo")
    val pehash = peinfo_res.map(x=> (x._1, (Json.parse(x._3) \ "pehash").asOpt[String].getOrElse("Undefined"))).filter(x => !x._2.equals("Undefined")).map(x => new Knowledge_Base(x._1, "pehash", compress(x._2.getBytes), UUIDs.timeBased()))
    val imphash = peinfo_res.map(x=> (x._1, (Json.parse(x._3) \ "imphash").asOpt[String].getOrElse("Undefined"))).filter(x => !x._2.equals("Undefined")).map(x => new Knowledge_Base(x._1, "imphash", compress(x._2.getBytes), UUIDs.timeBased()))
    val signature = peinfo_res.map(x=> (x._1, get_digitalsig(x._3))).filter(x=> !x._2.equals("NONE")).map(x => new Knowledge_Base(x._1, "binary_signature", compress(x._2.getBytes), UUIDs.timeBased()))

    val yara_res = results.filter(x=> x._2 == "yara")
    val rules = yara_res.map(x=> (x._1, x._3.replaceAll("[^a-zA-Z0-9.,_]","").replace("yara","").replace("rule:",""))).map(x => new Knowledge_Base(x._1, "yara_rules", compress(x._2.getBytes), UUIDs.timeBased()))

    val cuckoo_res = results.filter(x=> x._2 == "CUCKOO")
    val domains = cuckoo_res.map(x=> (x._1, get_cuckoo_urls(x._3))).filter(x=> !x._2.equals("")).map(x => new Knowledge_Base(x._1, "called_domains", compress(x._2.getBytes), UUIDs.timeBased()))

    knowledge_base_rdd.union(pehash).union(imphash).union(signature).union(rules).union(domains).saveToCassandra(keyspace,knowledge_base_table, SomeColumns("object_id", "feature_type", "feature_value", "timestamp"))
  }
}
