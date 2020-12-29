// Databricks notebook source
// Start the session. Order up a wee slice o' 🍰.
scala.math.Pi

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC ##### Procedure Note.
// MAGIC 
// MAGIC This [Data overview](https://docs.databricks.com/data/data.html) article describes the procedures for deleting contents from the `FileStore`.  Specifically, the procedure looks like.
// MAGIC 
// MAGIC       `dbutils.fs.rm("FileStore/tables/smj_title_abstr.json", true)`

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC dbutils.fs.rm("/dbfs", true)

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC These are stopwords used in a DataBricks demo of LDA.  We only need to acquire once.  It percists in the `tmp` directory.
// MAGIC 
// MAGIC %sh wget http://ir.dcs.gla.ac.uk/resources/linguistic_utils/stop_words -O /tmp/stopwords

// COMMAND ----------

// Import libraries
import org.apache.spark.ml.feature._
import org.apache.spark.sql.types._
import org.apache.spark.sql._

import java.text.SimpleDateFormat
import java.util.{Calendar, Date}

import scala.io.Source
import java.security.MessageDigest
import scala.collection.JavaConverters._
//import play.api.libs.json._
import spark.implicits._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.functions.{concat, lit, col}
import org.apache.spark.sql.{Column, DataFrame, Row, SparkSession, Dataset}
import org.apache.spark.ml.feature.{VectorAssembler, StringIndexer, StopWordsRemover, Tokenizer, CountVectorizer, HashingTF, IDF, OneHotEncoderEstimator, CountVectorizerModel, NGram}
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}
import org.apache.spark.ml.clustering.{KMeans,KMeansModel, LDA, LDAModel, LocalLDAModel, DistributedLDAModel}
import org.apache.spark.ml.evaluation.ClusteringEvaluator


import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.rdd.RDD

import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}



// COMMAND ----------

/* We want a hash key for the concatenated title abstract. This gives it to us.  
*/
def stringToMD5(digestString:String) : String = {
  return MessageDigest.getInstance("MD5")
                      .digest(digestString.toString
                                          .getBytes("UTF-8"))
                      .map("%02x".format(_))
                      .mkString("")
}

def cleanString(dirtyString:String) : String = {
  return dirtyString.replaceAll("[0-9]","")
                    .replaceAll("[()]","")
                    .replaceAll("[.]","")
                    .replaceAll("[?]","")
                    .replaceAll("$","")
                    .replaceAll(",","")
                    .replaceAll(";","")
                    .replaceAll(":","")
                    .replaceAll("\"","")
                    .replaceAll("-","")
}

val udfCleanString = udf(cleanString _)




// COMMAND ----------

val s : List[String] = List("1a", "2b", "3c")
s.flatMap(m => m.map(m2 => (m, m2))).toList(0)

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC ### Read in and set up data.  
// MAGIC 
// MAGIC ⓵ Read in all of the csv-format tables extracted from the JSON.
// MAGIC 
// MAGIC ⓶ Concatenate into a single DataFrame. This StackOverflow [How merge three DataFrame in Scala](https://stackoverflow.com/questions/49295616/how-merge-three-dataframe-in-scala) shows the use of the `daframe.union()` method. The [spark.apache.org](https://spark.apache.org/docs/latest/api/scala/org/package.html) project describes in detail in the [Dataset](https://spark.apache.org/docs/latest/api/scala/index.html#org.apache.spark.sql.Dataset) documentation.
// MAGIC 
// MAGIC ⓷ Combine `title` and `abstract` columns into a new column `titleAbstract`.  The procedure shown in the GA session on Scala dataframes failed in this situation.  This [Spark by {Examples}](https://sparkbyexamples.com/spark/spark-concatenate-dataframe-columns/) works, using `concat` and `col` methods.  Also, the [ApacheSpark](https://spark.apache.org/docs/2.0.0/index.html) contains a primer [Clustering - RDD-based API](https://spark.apache.org/docs/2.0.0/mllib-clustering.html)
// MAGIC 
// MAGIC ⓸ Introduce a hash column of the concatenated `(volume, issue, title)` tuple for each record. We use the `SHA1` method from [Class functions](https://spark.apache.org/docs/2.0.2/api/java/org/apache/spark/sql/functions.html#sha1(org.apache.spark.sql.Column) function set.  The `volIssueTitleHash` attribute provides a unique key for compressing the data and for subsequent joining.

// COMMAND ----------

val stratMgtJrnl = sqlContext.read
                             .format("csv")
                             .option("header", "true")
                             .option("inverSchema", "true")
                             .load("/GA_DSI10_DC-Hamlett/stratMgtJrnl.csv")
val stratEntrepren = sqlContext.read
                              .format("csv")
                              .option("header", "true")
                              .option("inverSchema", "true")
                              .load("/GA_DSI10_DC-Hamlett/stratEntrepren.csv")
val strategicOrg = sqlContext.read
                             .format("csv")
                             .option("header", "true")
                             .option("inverSchema", "true")
                             .load("/GA_DSI10_DC-Hamlett/strategicOrg.csv")
val intJrnlBizStrat = sqlContext.read
                                .format("csv")
                                .option("header", "true")
                                .option("inverSchema", "true")
                                .load("/GA_DSI10_DC-Hamlett/intJrnlBizStrat.csv")
val jrnlBizStrat = sqlContext.read
                              .format("csv")
                              .option("header", "true")
                              .option("inverSchema", "true")
                              .load("/GA_DSI10_DC-Hamlett/jrnlBizStrat.csv")
val jrnlBizStrategies = sqlContext.read
                                  .format("csv")
                                  .option("header", "true")
                                  .option("inverSchema", "true")
                                  .load("/GA_DSI10_DC-Hamlett/jrnlBizStrategies.csv")
val cmrTitleAbstr = sqlContext.read
                              .format("csv")
                              .option("header", "true")
                              .option("inverSchema", "true")
                              .load("/GA_DSI10_DC-Hamlett/cmrTitleAbstr.csv")
                              .withColumn("pubDate", lit("")) 
val bizMgtStrategy = sqlContext.read
                               .format("csv")
                               .option("header", "true")
                               .option("inverSchema", "true")
                               .load("/GA_DSI10_DC-Hamlett/bizMgtStrategy.csv")
                               .withColumn("pubDate", lit("")) 


var stratLitCorpusComponents = cmrTitleAbstr.union(bizMgtStrategy)
                                             .union(jrnlBizStrategies)
                                             .union(jrnlBizStrat)
                                             .union(intJrnlBizStrat)
                                             .union(strategicOrg)
                                             .union(stratEntrepren)
                                             .union(stratMgtJrnl)
                                             .withColumn("titleAbstract", 
                                                         concat(col("title"),
                                                                lit(" "), 
                                                                col("abstract"))) 
                                             .withColumn("volIssueTitleHash", 
                                                         sha1(concat(col("volume"),
                                                                     col("issue"),
                                                                     col("title"))).cast("String"))

stratLitCorpusComponents.printSchema



// COMMAND ----------

stratLitCorpusComponents.show()

// COMMAND ----------

// MAGIC %md
// MAGIC #### Data Cleansing. 
// MAGIC 
// MAGIC We need to add columns to our dataframe.  Our `titleAbstract` strings contain numbers, punctuation, which we don't want in our model.  It is easier to strip them out through a series of `string.replaceAll` operations than try to deal with them in stopwords.  Getting the `titleAbstract` into a list, to which we can apply our cleansing is easy enough.  Getting it back into the dataframe is the problem.
// MAGIC 
// MAGIC 
// MAGIC 
// MAGIC This StackOverflow [Spark: Applying UDF to Dataframe Generating new Columns based on Values in DF](https://stackoverflow.com/questions/42643737/spark-applying-udf-to-dataframe-generating-new-columns-based-on-values-in-df) describes a procedure for applying logic instantiated in a user-defined function to a dataframe column using the `withColum` method.  Specifically:
// MAGIC 
// MAGIC ⓵ We create a user-defined function `cleanString` to realize our cleansing logic;
// MAGIC 
// MAGIC ⓶ Register the function using the `udf` function; and
// MAGIC 
// MAGIC ⓷ Apply to our column via the `DataFrame.withColumn("newColumn", udf(c("oldColumn")))` method.
// MAGIC 
// MAGIC Ater this, we drop and rename dataframes.

// COMMAND ----------

var stratLitCorpus = stratLitCorpusComponents.withColumn("titleAbstractClean", udfCleanString(col("titleAbstract")))
                                             .drop("titleAbstract")
                                             .withColumnRenamed("titleAbstractClean", "titleAbstract")
                                             .drop("keywords")

// COMMAND ----------

stratLitCorpus.printSchema

// COMMAND ----------

stratLitCorpus.show()

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC #### Construct list of StopWords
// MAGIC 
// MAGIC We combine three sources of stopwords.
// MAGIC 
// MAGIC ⓵ The default `english` set described by [Class StopWordsRemover](https://spark.apache.org/docs/2.3.0/api/java/org/apache/spark/ml/feature/StopWordsRemover.html#loadDefaultStopWords-java.lang.String-) embedded in the library;
// MAGIC 
// MAGIC ⓶ An extended set identified in a DataBricks primer [Topic Modeling with Latent Dirichlet Allocation](https://databricks-prod-cloudfront.cloud.databricks.com/public/4027ec902e239c93eaaa8714f173bcfc/3741049972324885/3783546674231782/4413065072037724/latest.html) on the use of the [Class LDA](https://spark.apache.org/docs/latest/api/java/org/apache/spark/mllib/clustering/LDA.html); and
// MAGIC 
// MAGIC ⓷ A small number of additional stopwords of editorial relevance, but unimportant to content analysis.

// COMMAND ----------

var stopWordsFromPrimer = sc.textFile("/tmp/stopwords").collect() ++ Range('a', 'z').map(_.toChar.toString) ++ Array("")
var defaultEnglishStopWords = StopWordsRemover.loadDefaultStopWords("english")
val workingStopWordSet = stopWordsFromPrimer ++
                       defaultEnglishStopWords ++
                       Array("article", "case", "study", "explores", "business", "model", "ecomagination", "special", "issue",
                             "strategy", "strategies", "strategic", "new", "january", "february", "march", "april", "may",
                            "june", "july", "august", "september", "october", "november", "december", "copyright", "john",
                             "wiley", "sons", "ltd", "&", "management","performance","corporate","firms","firm", "industry")



// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC ### Basic  Model Setup
// MAGIC 
// MAGIC We begin with walking through the procedures presented during GA-DSI.  This involves count-vectorization using [`CountVectorizer`](https://spark.apache.org/docs/2.2.0/api/java/org/apache/spark/ml/feature/CountVectorizer.html).  This step was not successfully executed during the [Pipelines and Cross Validatition](https://git.generalassemb.ly/hamlett-neil-ga/7.06-lesson-spark-pipelines/blob/master/20200207%20Pipelines%20and%20Cross%20Validation%20Session.html). [ProgramCreek](https://www.programcreek.com/scala/org.apache.spark.ml.feature.CountVectorizer) offers illustrations that show its features.
// MAGIC 
// MAGIC Later, we attempt construct a model matrix using a TF-IDF approach.  The apache.spark article [Feature Extraction and Transformation - RDD-based API](https://spark.apache.org/docs/latest/mllib-feature-extraction.html#feature-extraction-and-transformation-rdd-based-api) provides our pattern.   We are also guided by [Clustering - RDD-based API](https://spark.apache.org/docs/latest/mllib-clustering.html) and [Clustering](https://spark.apache.org/docs/latest/ml-clustering.html) primers, also by [apache.spark](https://spark.apache.org).
// MAGIC 
// MAGIC Following the [Topic Modeling with Latent Dirichlet Allocation](https://databricks-prod-cloudfront.cloud.databricks.com/public/4027ec902e239c93eaaa8714f173bcfc/3741049972324885/3783546674231782/4413065072037724/latest.html) pattern we assign the label `text` to our `VectorAssembler()` output. A [medium.com] article [LDA Topic Modeling in Spark MLlib](https://medium.com/zero-gravity-labs/lda-topic-modeling-in-spark-mllib-febe84b9432) shows the model setup without the packages.  The [apache.org](https://spark.apache.org/docs/2.0.0/api/scala/org/package.html) official documentation appears in [LDA](https://spark.apache.org/docs/2.0.0/api/scala/index.html#org.apache.spark.mllib.clustering.LDA).
// MAGIC 
// MAGIC #### Approach.
// MAGIC 
// MAGIC In anticipation of subsequent GridSearch, we instantiate our model as a [`org.apache.spark.ml.Pipeline`](https://spark.apache.org/docs/latest/ml-pipeline.html).  We follow verbatim here the procedure laid out in a **primer** [Topic Modeling with Latent Dirichlet Allocation](https://databricks-prod-cloudfront.cloud.databricks.com/public/4027ec902e239c93eaaa8714f173bcfc/3741049972324885/3783546674231782/4413065072037724/latest.html).  
// MAGIC 
// MAGIC 🅐 The construction of our `Pipeline()` follows the following steps.
// MAGIC 
// MAGIC ⓵ We use [org.apache.spark.ml.feature.Tokenizer](https://spark.apache.org/docs/latest/ml-features#tokenizer) to tokenize our corpus.  This is in the `titleAbstract` column of our `stratLitCorpus` dataframe.  Technical details appear in the [spark.apache.org](https://spark.apache.org/) website at [Class Tokenizer](https://spark.apache.org/docs/2.3.0/api/java/index.html?org/apache/spark/ml/feature/Tokenizer.html).  We later will also explore [Class NGram](t.ly/Wx26m). Combining `ngrams` and tokens into a single vocabulary isn't automatic.  This StackOverflow [How to combine n-grams into one vocabulary in Spark?](https://stackoverflow.com/questions/38839924/how-to-combine-n-grams-into-one-vocabulary-in-spark) describes a procedure.
// MAGIC 
// MAGIC ⓶ We next remove stopwords using [org.apache.spark.ml.feature.StopWordsRemover](https://spark.apache.org/docs/latest/ml-features#stopwordsremover).  Our [primer](https://databricks-prod-cloudfront.cloud.databricks.com/public/4027ec902e239c93eaaa8714f173bcfc/3741049972324885/3783546674231782/4413065072037724/latest.html) gave us a source of stopwords.  Technical details appear at [StopWordsRemover](https://spark.apache.org/docs/2.3.0/api/java/index.html?org/apache/spark/ml/feature/Tokenizer.html).  
// MAGIC 
// MAGIC ⓷ [org.apache.spark.ml.feature.VectorAssembler](https://spark.apache.org/docs/latest/ml-features.html#vectorassembler) nextcombines a given list of columns into a single vector column.  [Class VectorAssembler](t.ly/2XqJR) contains its technical details.
// MAGIC 
// MAGIC ⓸ Finally, we declare our [org.apache.spark.ml.clustering.LDA](https://spark.apache.org/docs/latest/ml-clustering.html#latent-dirichlet-allocation-lda) model.  Its technical detals appear at [LDA](https://spark.apache.org/docs/latest/api/scala/index.html#org.apache.spark.ml.clustering.LDA).  Following our [primer](https://databricks-prod-cloudfront.cloud.databricks.com/public/4027ec902e239c93eaaa8714f173bcfc/3741049972324885/3 783546674231782/4413065072037724/latest.html), we select "EM" as our `optimizer` setting.  This gives the [`DistributedLDAModel`](https://spark.apache.org/docs/latest/api/scala/index.html#org.apache.spark.ml.clustering.DistributedLDAModel), containing inferred topics, full training staset, and topic ditribution for each training document.
// MAGIC 
// MAGIC 🅑 Our [primer](https://databricks-prod-cloudfront.cloud.databricks.com/public/4027ec902e239c93eaaa8714f173bcfc/3741049972324885/3783546674231782/4413065072037724/latest.html) also gives us a procedure for extracting the topic distributions.
// MAGIC 
// MAGIC ⓵ We extract from our pipeline `stratLitModMtxPipe` the vocabulary.  We instantiate an instance of [`CountVectorizerModel`](https://spark.apache.org/docs/latest/api/scala/index.html#org.apache.spark.ml.feature.CountVectorizerModel), which yields the vocabulary.
// MAGIC 
// MAGIC #### Mechanical detail.
// MAGIC 
// MAGIC We need a cross-product operation.  We use it for GridSearch.  This StackOverflow [Cross product of 2 sets in Scala](https://stackoverflow.com/questions/2725682/cross-product-of-2-sets-in-scala) — Apparently written by a humble-bragger — provides a compact solution.

// COMMAND ----------

val setA = List(1,2,3)
val setB = List("a", "b")
val setC = List("😃", "🤮")
(for (x <- setA; y <- setB; z <- setC) yield (x, y, z))



val ldaKGrid = List(15)
val countVecVocab = List(750,875)
val countVecMaxDf = List(0.6)




// COMMAND ----------

val ldaKGrid = List(10,13,15,18,20)
val countVecVocab = List(750,875,1000)
val countVecMaxDf = List(0.6,0.8)


val ldaSearchGrid = (for (ξ <- ldaKGrid; η <- countVecVocab; ζ <- countVecMaxDf) yield (ξ, η, ζ))

// COMMAND ----------

/* As a first step, we tokenize the `titleAbstract` following the procedure presented
   during GA Pipelines and Xval session.  */
def ldaFunction(gridPoint:(Int, Int, Double)) : ((Int, Int, Double), Double, String) = {
  // Define a formatter for a timestamp.
  val formatter = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss")
  /*
      Print the status*/  
  println(formatter.format(Calendar.getInstance().getTime()), gridPoint)
  var gridSearchState = Array.empty[((Int, Int, Double), Double, String)]
  /*
      Tokenize the titleAbstract string.*/  
  var stratLitTokenizer = new Tokenizer().setInputCol("titleAbstract")
                                          .setOutputCol("tokens")
  /*
      Remove StopWords.*/  
  var stratLitStops = new StopWordsRemover().setInputCol(stratLitTokenizer.getOutputCol)
                                            .setOutputCol("stopFreeTokens")
                                            .setStopWords(workingStopWordSet)
  /*
      Assemble stopword-free tokens into N-grams.  We do not use this at this instance.*/  
  var stratLitNgrams = new NGram().setN(1)
                                  .setInputCol(stratLitStops.getOutputCol)
                                  .setOutputCol("features")
  /*
      Count-0vectorize the stopword-free tokens.*/  
  var stratLitCountVec = new CountVectorizer().setInputCol(stratLitStops.getOutputCol)
                                              .setOutputCol("features")
                                              .setVocabSize(gridPoint._2)
                                              .setMaxDF(gridPoint._3)
  /*
      Instantiate the LDA model.*/  
  var stratLiltLda = new LDA().setK(gridPoint._1)
                              .setMaxIter(75)
                              .setOptimizer("em")
  /*
      Instantiate a data-transformation-only pipeline. We use this strictly
      for development, trouble-shooting.*/  
  var stratLitCvecPipe = new Pipeline().setStages(Array(stratLitTokenizer,
                                                         stratLitStops,
                                                         stratLitCountVec))
  /*
      Instantiate and fit a pipeline that includes the LDA model.*/ 
  var stratLitCvecLdaPipe = new Pipeline().setStages(Array(stratLitTokenizer,
                                                           stratLitStops,
                                                           stratLitCountVec,
                                                           stratLiltLda))
  var stratLitCvecLdaFit = stratLitCvecLdaPipe.fit(stratLitCorpus)
  /*
      Extract from our model three segments. These are the count-vectorizer model,
      the LDA distributed model, anbd the LDA model. The LDA distributed model
      yields our grid-search model-performance metric.*/ 
  var stratLitVecModel = stratLitCvecLdaFit.stages(2)
                                       .asInstanceOf[CountVectorizerModel]
  val stratLitLdaDistModel = stratLitCvecLdaFit.stages(3)
                                           .asInstanceOf[DistributedLDAModel]
  val stratLitLdaModel = stratLitCvecLdaFit.stages(3)
                                       .asInstanceOf[LDAModel]
  /*
      Print out the state.*/ 
  println(formatter.format(Calendar.getInstance().getTime()), gridPoint)
  /*
      Return a tuple including the gridpoint, the log-likelihood score, and a timestamp.*/ 
  return (gridPoint, stratLitLdaDistModel.trainingLogLikelihood, formatter.format(Calendar.getInstance().getTime()))}

// COMMAND ----------

// Apply a map-function operator to the gridSearch grid.
val stratLitLdaGridSearch = ldaSearchGrid.map(ξ => ldaFunction(ξ))
/*
   Define the filepath to which we save csv-formatted results.*/
var filePath = "GA_DSI10_DC-Hamlett/" + formatter.format(Calendar.getInstance().getTime()) + "_ldaGridSearch.csv"
/*
    Take the ouptut of our GridSearch, convert the results to a dataframe,
    and save to a csv file in the FileStore.*/
stratLitLdaGridSearch.toDF("gridTuple", "logLikelihood", "time")
                     .withColumn("gridTupleString",
                                 col("gridTuple").cast("String"))
                     .drop("gridTuple")
                    .repartition(1) 
                    .write
                    .format("com.databricks.spark.csv")
                    .mode("overwrite")
                    .option("header", "true")
                    .save(filePath)

// COMMAND ----------



// COMMAND ----------



// COMMAND ----------

var gridSearchState = Array.empty[((Int, Int, Double), Double, String)]

// COMMAND ----------

stratLitLdaGridSearch(0)

// COMMAND ----------

gridSearchState = gridSearchState :+ (stratLitLdaGridSearch(0)._1, stratLitLdaGridSearch(0)._2, formatter.format(Calendar.getInstance().getTime()))

// COMMAND ----------

gridSearchState

// COMMAND ----------

gridSearchState.toList.toDF("gridTuple", "logLikelihood", "timeStamp").show

// COMMAND ----------

stratLitLdaGridSearch.toDF("gridTuple", "logLikelihood").show()

// COMMAND ----------

ldaSearchGrid(0)

// COMMAND ----------

val zipUDF = udf { (terms: Seq[String], probabilities: Seq[Double]) => terms.zip(probabilities) }


// COMMAND ----------

stratLitCvecLdaFit.transform(stratLitCorpus).count




// COMMAND ----------

stratLitLdaModel.explainParams()

// COMMAND ----------

val stratLitVocab = stratLitVecModel.vocabulary
val termsIdx2Str = udf { (termIndices: Seq[Int]) => termIndices.map(idx => stratLitVocab(idx)) }


val topics = stratLitLdaDistModel.describeTopics(maxTermsPerTopic = 5)
  .withColumn("terms", termsIdx2Str(col("termIndices")))
display(topics.select("topic", "terms", "termWeights"))

// COMMAND ----------

topics.withColumn("termWithProb", explode(zipUDF(col("terms"), col("termWeights")))).show()

// COMMAND ----------

val zipUDF = udf { (terms: Seq[String], probabilities: Seq[Double]) => terms.zip(probabilities) }
val topicsTmp = topics.withColumn("termWithProb", explode(zipUDF(col("terms"), col("termWeights"))))
val termDF = topicsTmp.select(
  col("topic").as("topicId"),
  col("termWithProb._1").as("term"),
  col("termWithProb._2").as("probability"))

display(termDF)

// COMMAND ----------

// Create JSON data
val rawJson = termDF.toJSON.collect().mkString(",\n")


// COMMAND ----------

displayHTML(s"""
<!DOCTYPE html>
<meta charset="utf-8">
<style>

circle {
  fill: rgb(31, 119, 180);
  fill-opacity: 0.5;
  stroke: rgb(31, 119, 180);
  stroke-width: 1px;
}

.leaf circle {
  fill: #ff7f0e;
  fill-opacity: 1;
}

text {
  font: 14px sans-serif;
}

</style>
<body>
<script src="https://cdnjs.cloudflare.com/ajax/libs/d3/3.5.5/d3.min.js"></script>
<script>

var json = {
 "name": "data",
 "children": [
  {
     "name": "topics",
     "children": [
      ${rawJson}
     ]
    }
   ]
};

var r = 1500,
    format = d3.format(",d"),
    fill = d3.scale.category20c();

var bubble = d3.layout.pack()
    .sort(null)
    .size([r, r])
    .padding(1.5);

var vis = d3.select("body").append("svg")
    .attr("width", r)
    .attr("height", r)
    .attr("class", "bubble");

  
var node = vis.selectAll("g.node")
    .data(bubble.nodes(classes(json))
    .filter(function(d) { return !d.children; }))
    .enter().append("g")
    .attr("class", "node")
    .attr("transform", function(d) { return "translate(" + d.x + "," + d.y + ")"; })
    color = d3.scale.category20();
  
  node.append("title")
      .text(function(d) { return d.className + ": " + format(d.value); });

  node.append("circle")
      .attr("r", function(d) { return d.r; })
      .style("fill", function(d) {return color(d.topicName);});

var text = node.append("text")
    .attr("text-anchor", "middle")
    .attr("dy", ".3em")
    .text(function(d) { return d.className.substring(0, d.r / 3)});
  
  text.append("tspan")
      .attr("dy", "1.2em")
      .attr("x", 0)
      .text(function(d) {return Math.ceil(d.value * 10000) /10000; });

// Returns a flattened hierarchy containing all leaf nodes under the root.
function classes(root) {
  var classes = [];

  function recurse(term, node) {
    if (node.children) node.children.forEach(function(child) { recurse(node.term, child); });
    else classes.push({topicName: node.topicId, className: node.term, value: node.probability});
  }

  recurse(null, root);
  return {children: classes};
}
</script>
""")


// COMMAND ----------

// MAGIC %md
// MAGIC 🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑
// MAGIC 🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑  ⟱⟱⟱⟱⟱⟱⟱ WORKING SNIPPETS BELOW  ⟱⟱⟱⟱⟱⟱⟱ 🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑
// MAGIC 🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑🛑

// COMMAND ----------

/* We find the need to parse a `play.api.libs.json.JsArray` object to extract
   a specific column.  No approach to convert it to an indexable scala object
   is conspicuously forthcoming.  So, we brute-force it.  We convert our `JsArray1`
   to a string object and then parse that, returning only what we need.
*/
def jsArrayToArrayOfStrings (jsArrayString : String) : Array[String] = {
          jsArrayString.replaceAll("\\[", "" )
                       .replaceAll("\\]", "" )
                       .split("\",\"")
                       .filter(ζ => ζ.contains("Volume"))
                       .map(η => η.replaceAll("\"", ""))  }

// COMMAND ----------

def stringToMD5(digestString:String) : String = {
  return MessageDigest.getInstance("MD5")
                      .digest(digestString.toString
                                          .getBytes("UTF-8"))
                      .map("%02x".format(_))
                      .mkString("")
}

stringToMD5("The quick red fox jumped over the lazy brown dogs' backs.")

// COMMAND ----------

/*
/* As a first step, we tokenize the `titleAbstract` following the procedure presented
   during GA Pipelines and Xval session.  */
var stratLitTokenizer = new Tokenizer().setInputCol("titleAbstract")
                                        .setOutputCol("tokens")
var stratLitStops = new StopWordsRemover().setInputCol(stratLitTokenizer.getOutputCol)
                                          .setOutputCol("stopFreeTokens")
                                          .setStopWords(workingStopWordSet)
var stratLitNgrams = new NGram().setN(1)
                                .setInputCol(stratLitStops.getOutputCol)
                                .setOutputCol("features")
var stratLitCountVec = new CountVectorizer().setInputCol(stratLitStops.getOutputCol)
                                            .setOutputCol("features")
                                            .setVocabSize(800)
                                            .setMinDF(5)


var stratLiltLda = new LDA().setK(31)
                            .setMaxIter(50)
                            .setOptimizer("em")


var stratLitCvecPipe = new Pipeline().setStages(Array(stratLitTokenizer,
                                                       stratLitStops,
                                                       stratLitCountVec))
var stratLitCvecPipeXformer = stratLitCvecPipe.fit(stratLitCorpus)
var stratLitCvecModelMtx = stratLitCvecPipeXformer.transform(stratLitCorpus)


var stratLitCvecLdaPipe = new Pipeline().setStages(Array(stratLitTokenizer,
                                                         stratLitStops,
                                                         stratLitCountVec,
                                                         stratLiltLda))

var stratLitCvecLdaFit = stratLitCvecLdaPipe.fit(stratLitCorpus)





var stratLitVecModel = stratLitCvecLdaFit.stages(2)
                                     .asInstanceOf[CountVectorizerModel]



val stratLitLdaDistModel = stratLitCvecLdaFit.stages(3)
                                         .asInstanceOf[DistributedLDAModel]

val stratLitLdaModel = stratLitCvecLdaFit.stages(3)
                                     .asInstanceOf[LDAModel]

stratLitLdaDistModel.trainingLogLikelihood

*/
