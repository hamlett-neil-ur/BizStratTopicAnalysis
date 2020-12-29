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
// MAGIC dbutils.fs.rm("FileStore/tables/200407articlesKnownTopics.csv", true)

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC These are stopwords used in a DataBricks demo of LDA.  We only need to acquire once.  It persists in the `tmp` directory.
// MAGIC 
// MAGIC %sh wget http://ir.dcs.gla.ac.uk/resources/linguistic_utils/stop_words -O /dbfs/GA_DSI10_DC-Hamlett/stopwords

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC These are the `coordinates` for our `spark-nlp` library.
// MAGIC 
// MAGIC ###    com.johnsnowlabs.nlp:spark-nlp_2.11:2.4.4            

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC Here we extend a pattern by DataBricks [Topic Modeling with Latent Dirichlet Allocation](https://databricks-prod-cloudfront.cloud.databricks.com/public/4027ec902e239c93eaaa8714f173bcfc/3741049972324885/3783546674231782/4413065072037724/latest.html) to our own purposes.
// MAGIC 
// MAGIC We use the John Snow Labs NLP package `com.johnsnowlabs.nlp:spark-nlp_2.11:2.4.4` for certain functions, e.g., Lemmantization.  [Getting started in Databricks](https://johnsnowlabs.github.io/spark-nlp-workshop/databricks/index.html#Getting%20Started.html) is a primer illustrating how to get started, including loading the  packages.  As per Slack trasaction with johnsnowlabs support, this doesn't work for all DataBricks configurations.  It is supported as of Sunday, March 22, 2020 for `6.2 (includes Apache Spark 2.4.4, Scala 2.11)` and `6.3 (includes Apache Spark 2.4.4, Scala 2.11)`.  The `ML` configurations are not supported. John Snow Labs' GitHub [Databricks newer runtime versions and ML editions serialization issue #832](https://github.com/JohnSnowLabs/spark-nlp/issues/832) provides detais.
// MAGIC 
// MAGIC We are specifically interested in Annotators.  Two primers are available. [Annotators Guideline](https://nlp.johnsnowlabs.com/docs/en/annotators) is on John Snow Labs' website.   [Annotators Guideline](https://github.com/JohnSnowLabs/spark-nlp/blob/master/docs/en/annotators.md) is a GitHub.  Also, [annotators](https://nlp.johnsnowlabs.com/api/index#com.johnsnowlabs.nlp.annotators.package) contains an API description in a form whose look and feel is identical to that by [apache.spark](https://spark.apache.org/docs/latest/api/scala/index.html#package). [Getting started in Databricks](https://johnsnowlabs.github.io/spark-nlp-workshop/databricks/index.html#Getting%20Started.html) shows `import com.johnsnowlabs.nlp.annotator._`, which indiscriminately loads all annotators. Some of these conflict with classses in `import org.apache.spark.ml.feature`.  We have successfully used classes in the latter. 
// MAGIC 
// MAGIC 
// MAGIC We therefore selectively load specific classes from `import com.johnsnowlabs.nlp.annotator` that we want to use. We specifically begin with `Lemmatizer`, `Stemmer`, and `Normalizer`.
// MAGIC 
// MAGIC 
// MAGIC #### Spark DataFrame Class
// MAGIC 
// MAGIC For reference, [org.apache.spark.sql Class DataFrame](https://spark.apache.org/docs/1.4.0/api/java/org/apache/spark/sql/DataFrame.html)
// MAGIC 
// MAGIC 
// MAGIC #### Spark SQL Functions
// MAGIC 
// MAGIC This [org.apache.spark.sql Class Functions](https://spark.apache.org/docs/1.6.0/api/java/org/apache/spark/sql/functions.html) is indespensible for manipulating dataframes.

// COMMAND ----------

// Import libraries
import java.io._
import org.apache.spark.ml.feature._
import org.apache.spark.sql.types._
import org.apache.spark.sql._
import scala.io.Source
import java.security.MessageDigest
import java.util.Calendar
import java.text.SimpleDateFormat
import scala.collection.JavaConverters._
//import play.api.libs.json._
import spark.implicits._
import org.apache.spark.sql.functions._
//import org.apache.spark.sql.functions.{concat, lit, col}
import org.apache.spark.sql.{Column, DataFrame, Row, SparkSession, Dataset}
//import org.apache.spark.ml.feature.{VectorAssembler, StringIndexer, StopWordsRemover, CountVectorizer, HashingTF, IDF, OneHotEncoderEstimator, CountVectorizerModel, NGram}
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.clustering.{KMeans,KMeansModel, LDA, LDAModel, LocalLDAModel, DistributedLDAModel}
import org.apache.spark.ml.feature.{VectorAssembler, StringIndexer, StopWordsRemover, CountVectorizer, HashingTF, IDF, OneHotEncoderEstimator, CountVectorizerModel, NGram}

//import org.apache.spark.ml.evaluation.ClusteringEvaluator
//import org.apache.spark.ml.linalg.{DenseVector, SparseVector, Vector}
import org.apache.spark.ml.linalg._
//import breeze.linalg.{DenseMatrix, DenseVector}

import org.apache.spark.rdd.RDD

import com.johnsnowlabs.nlp.SparkNLP
import com.johnsnowlabs.nlp.base._
import com.johnsnowlabs.nlp.annotator._  //{Tokenizer, TokenizerModel, Lemmatizer, LemmatizerModel, Normalizer, NormalizerModel, Stemmer}
import com.johnsnowlabs.nlp.pretrained._


import org.apache.spark.ml.linalg.DenseVector



// COMMAND ----------

val formatter = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss")
val calendar = Calendar.getInstance()
formatter.format(calendar.getTime())+"Z"

// COMMAND ----------

// MAGIC %md
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

/* We want a hash key for the concatenated title abstract. This gives it to us.  
*/
def stringToMD5(digestString:String) : String = {
  return MessageDigest.getInstance("MD5")
                      .digest(digestString.toString
                                          .getBytes("UTF-8"))
                      .map("%02x".format(_))
                      .mkString("")
}

val udfStringToMd5 = udf(stringToMD5 _)

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
                    .replaceAll("-"," ")
                    .replaceAll("/"," ")
}

val udfCleanString = udf(cleanString _)


/* This user-defined function takes the `Any`-instantiated `denseVector` into which the
   `topicDistrition` produced by `LDA().transform()` is encoded, and converts it into
   an `Array[Double]`. We instaniate using the `import org.apache.spark.sql..udf` function
   so that we can call it using the `DataFrame.withColumn("newColumn", udf(col("oldColumn")))`
   method.    */
def anyDenseVectorToArray(anyDenseVec:Any) : Array[Double] ={
  anyDenseVec.asInstanceOf[DenseVector].toArray
}
val udfAnyDvecToArr = udf(anyDenseVectorToArray _)

/* 
   We subsequently find that the count-vectorization stage produces records for which no surviving 
   features exist.  That is, none of the tokens qualify for inclusion in the maximum-length
   acceptable vocabulary. These no-feature or small-feature-count scenarios cause the
   LDA model to crash.  We therefore need to filtere these records out of the data applied
   to the LDA model.  
   
   CountVectorizer's `features` attribute is coded as a `SparseVector` class.  This is a tupe
   of elements (size, indices, counts).  We have to parse these `SparseVectors` to identify the
   records we don't want to apply to our LDA model.  Since the records are in a `DataFrame` Class,
   we use user-defined functions (UDFs) to perform the SQL-like operations on dataframe columns.
   We use two, here. `SparseVecIndices` extraces the `indices` attribute from the `SparseVector`.
   Then, `distTokenCount` identifies the number of distinct tokens contained with the 
   maximumn-length vocabulary.  This latter provides a quantity on which we can filter
   in order to data that the `LDA` model can handle. */
val sparseVecIndices: Any => Array[Int] = (_.asInstanceOf[SparseVector].indices)
val sparseVecIndicesUdf = udf(sparseVecIndices)

val sparseVecToArray : Any => Array[Double] = _.asInstanceOf[SparseVector].toArray
val sparseVecToArrayUdf = udf(sparseVecToArray)

val distTokentCount : Any => Int = _.asInstanceOf[Seq[Int]].distinct.length
val distTokenCountUdf = udf(distTokentCount)

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
                             .option("inferSchema", "true")
                             .load("/GA_DSI10_DC-Hamlett/strategicOrg.csv")
val intJrnlBizStrat = sqlContext.read
                                .format("csv")
                                .option("header", "true")
                                .option("inferSchema", "true")
                                .load("/GA_DSI10_DC-Hamlett/intJrnlBizStrat.csv")
val jrnlBizStrat = sqlContext.read
                              .format("csv")
                              .option("header", "true")
                              .option("inferSchema", "true")
                              .load("/GA_DSI10_DC-Hamlett/jrnlBizStrat.csv")
val jrnlBizStrategies = sqlContext.read
                                  .format("csv")
                                  .option("header", "true")
                                  .option("inferSchema", "true")
                                  .load("/GA_DSI10_DC-Hamlett/jrnlBizStrategies.csv")
val cmrTitleAbstr = sqlContext.read
                              .format("csv")
                              .option("header", "true")
                              .option("inferSchema", "true")
                              .load("/GA_DSI10_DC-Hamlett/cmrTitleAbstr.csv")
                              .withColumn("pubDate", lit("")) 
val bizMgtStrategy = sqlContext.read
                               .format("csv")
                               .option("header", "true")
                               .option("inferSchema", "true")
                               .load("/GA_DSI10_DC-Hamlett/bizMgtStrategy.csv")
                               .withColumn("pubDate", lit("")) 


var stratLitCorpusComponents = cmrTitleAbstr.union(bizMgtStrategy)
                                             .union(jrnlBizStrategies)
                                             .union(jrnlBizStrat)
                                             .union(intJrnlBizStrat)
                                             .union(strategicOrg)
                                             .union(stratEntrepren)
                                             .union(stratMgtJrnl)
                                             .distinct()
                                             .withColumn("titleAbstract", 
                                                         concat(col("title"),
                                                                lit(" "), 
                                                                col("abstract"))) 
                                             .withColumn("volIssueTitleHash", 
                                                         udfStringToMd5(concat(col("volume"),
                                                                               col("issue"),
                                                                               col("title"),
                                                                               col("journal"))).cast("String"))

stratLitCorpusComponents.printSchema

var docsKnownTopic = sqlContext.read
                               .format("csv")
                               .option("header", "true")
                               .option("inferSchema", "true")
                               .load("/FileStore/tables/200407articlesKnownTopics.csv")
                               .withColumn("titleAbstract", 
                                           lower(concat(col("title"),                             // This is a handful of documents strongly representative
                                                  lit(" "),                                       // of the dominant themes we are trying to detect in the corpus.
                                                  col("abstract"))))                              // We apply to them a processing stream identical to that 
                               .withColumn("volIssueTitleHash",                                   // of our "training" corpus.  We seek to find the topics
                                           udfStringToMd5(col("titleAbstract")).cast("String"))   // for which the produce the strongest signal.
 

// COMMAND ----------

display(docsKnownTopic)

// COMMAND ----------

stratLitCorpusComponents.show(5)

// COMMAND ----------

/* Here, we save a slice of our corpus to a csv file in the dataStore. We particuarly
   want `journal`, `volume`, `pubDate`, `issue`, `title`, and `volIssueTitleHash`. This
   allows for data reduction as we export the model ouputs for analysis and visualization 
   in python.*/
stratLitCorpusComponents.select("journal",
                                "volume",
                                "pubDate",
                                "issue",
                                "title",
                                "volIssueTitleHash")
                        .repartition(1) 
                        .write
                        .format("com.databricks.spark.csv")
                        .mode("overwrite")
                        .option("header", "true")
                        .save("/GA_DSI10_DC-Hamlett/stratLitCorpusProfile.csv")
docsKnownTopic.select("journal",
                      "volume",
                      "pubDate",
                      "issue",
                      "title",
                      "volIssueTitleHash")
              .repartition(1) 
              .write
              .format("com.databricks.spark.csv")
              .mode("overwrite")
              .option("header", "true")
              .save("/GA_DSI10_DC-Hamlett/docsKnownTopic.csv")

// COMMAND ----------

display(docsKnownTopic.select("journal",
                      "volume",
                      "pubDate",
                      "issue",
                      "title",
                      "volIssueTitleHash"))

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

var stratLitCorpus = stratLitCorpusComponents.withColumn("titleAbstractClean", 
                                                         udfCleanString(col("titleAbstract")))
                                             .drop("titleAbstract")
                                             .drop("abstract")
                                             .withColumnRenamed("titleAbstractClean", "titleAbstract")
                                             .drop("keywords")
docsKnownTopic = docsKnownTopic.withColumn("titleAbstractClean", 
                                           udfCleanString(col("titleAbstract")))
                               .drop("titleAbstract")
                               .drop("abstract")
                               .withColumnRenamed("titleAbstractClean", "titleAbstract")
                               .select("volIssueTitleHash", "titleAbstract")

// COMMAND ----------

docsKnownTopic.show

// COMMAND ----------

stratLitCorpus.printSchema

// COMMAND ----------

stratLitCorpus.show(5)

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

/*var stopWordsFromPrimer = sc.textFile("/GA_DSI10_DC-Hamlett/stopwords")
                           .collect() ++ Range('a', 'z')
                           .map(_.toChar.toString) ++ Array("")*/

var stopWordsFromPrimer = sc.textFile("/tmp/stopwords")
                            .collect() ++ Range('a', 'z')
                            .map(_.toChar.toString) ++ Array("") 
var defaultEnglishStopWords = StopWordsRemover.loadDefaultStopWords("english")
val workingStopWordSet = (stopWordsFromPrimer ++
                          defaultEnglishStopWords ++
                           Array("article", "case", "study", "explores", "business", "model", "ecomagination", "special", "issue", "journal",
                                 "strategy", "strategies", "strategic", "new", "january", "february", "march", "april", "may",
                                "june", "july", "august", "september", "october", "november", "december", "copyright", "john",
                                 "wiley", "sons", "ltd", "&", "management","performance","corporate","firms","firm", "industry", "©", 
                                "paper", "–", "research", "papers", "john", "copyright", "study", "studies", "wiley", "review", "acknowledgement",
                                "acknowledgements", "$", "%", "'", "literature")).distinct.sortWith(_ < _)



// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC ### Basic  Model Setup
// MAGIC 
// MAGIC We begin with walking through the procedures presented during GA-DSI.  This involves count-vectorization using [`CountVectorizer`](https://spark.apache.org/docs/2.2.0/api/java/org/apache/spark/ml/feature/CountVectorizer.html).  This step was not successfully executed during the [Pipelines and Cross Validatition](https://git.generalassemb.ly/hamlett-neil-ga/7.06-lesson-spark-pipelines/blob/master/20200207%20Pipelines%20and%20Cross%20Validation%20Session.html). [ProgramCreek](https://www.programcreek.com/scala/org.apache.spark.ml.feature.CountVectorizer) offers illustrations that show its features.
// MAGIC 
// MAGIC Later, we attempt construct a model matrix using a TF-IDF approach.  The apache.spark article [Feature Extraction and Transformation - RDD-based API](https://spark.apache.org/docs/latest/mllib-feature-extraction.html#feature-extraction-and-transformation-rdd-based-api) provides our pattern.   We are also guided by [Clustering - RDD-based API](https://spark.apache.org/docs/latest/mllib-clustering.html) and [Clustering](https://spark.apache.org/docs/latest/ml-clustering.html) primers, also by [apache.spark](https://spark.apache.org).
// MAGIC 
// MAGIC Following the [Topic Modeling with Latent Dirichlet Allocation](https://databricks-prod-cloudfront.cloud.databricks.com/public/4027ec902e239c93eaaa8714f173bcfc/3741049972324885/3783546674231782/4413065072037724/latest.html) pattern we assign the label `text` to our `VectorAssembler()` output. A [medium.com] article [LDA Topic Modeling in Spark MLlib](https://medium.com/zero-gravity-labs/lda-topic-modeling-in-spark-mllib-febe84b9432) shows the model setup without the packages.  The [apache.org](https://spark.apache.org/docs/2.0.0/api/scala/org/package.html) official documentation appears in [`LDA`](https://spark.apache.org/docs/2.0.0/api/scala/index.html#org.apache.spark.mllib.clustering.LDA).
// MAGIC 
// MAGIC #### Approach.
// MAGIC 
// MAGIC In anticipation of subsequent GridSearch, we instantiate our model as a [`org.apache.spark.ml.Pipeline`](https://spark.apache.org/docs/latest/ml-pipeline.html). Our pipeline stages consist of all of the functions in the [Transformers](https://nlp.johnsnowlabs.com/docs/en/transformers) [Annotators](https://nlp.johnsnowlabs.com/docs/en/annotators) from [spark-nlp](https://www.johnsnowlabs.com/).  We perform a straightforward transformation of the pipeline output to make the format compatible with [`CountVectorizer`](https://spark.apache.org/docs/latest/api/scala/index.html#org.apache.spark.ml.feature.CountVectorizer).  Our gridsearch parameter space will span parameters for [`CountVectorizer`](https://spark.apache.org/docs/latest/api/scala/index.html#org.apache.spark.ml.feature.CountVectorizer) and [`org.apache.spark.ml.clustering.LDA`](https://spark.apache.org/docs/latest/api/scala/index.html#org.apache.spark.ml.clustering.LDA).
// MAGIC 
// MAGIC 🅐 The construction of our annotation `Pipeline()` follows the following steps. We are using the [Transformers](https://nlp.johnsnowlabs.com/docs/en/transformers) [Annotators](https://nlp.johnsnowlabs.com/docs/en/annotators) from [`spark-nlp`](https://www.johnsnowlabs.com/). 
// MAGIC 
// MAGIC ⓵ The spark-nlp [`DocumentAssembler`](https://nlp.johnsnowlabs.com/docs/en/transformers#documentassembler-getting-data-in) is the first step.  Its API documentation is [`DocumentAssembler`](https://nlp.johnsnowlabs.com/api/#com.johnsnowlabs.nlp.DocumentAssembler). 
// MAGIC 
// MAGIC ⓶ The [`SentenceDetector`](https://nlp.johnsnowlabs.com/docs/en/annotators#sentencedetector) comes next.    
// MAGIC 
// MAGIC ⓷ As usual, our sentences are decoposed into tokens by the [`Tokenizer`](https://nlp.johnsnowlabs.com/docs/en/annotators#tokenizer).  The datailed API is [`Tokenizer`](https://nlp.johnsnowlabs.com/api/#com.johnsnowlabs.nlp.annotators.Tokenizer). 
// MAGIC 
// MAGIC ⓸ We next use the [`Stemmer`](https://nlp.johnsnowlabs.com/docs/en/annotators#stemmer).  Its API is at [`Stemmer`](https://nlp.johnsnowlabs.com/api/#com.johnsnowlabs.nlp.annotators.Stemmer).  This proves somewhat of a klunky way to truncate suffixes.  We prefer the [`Lemmatizer`](https://nlp.johnsnowlabs.com/docs/en/annotators#lemmatizer), with API [`Lemmatizer`](https://nlp.johnsnowlabs.com/api/#com.johnsnowlabs.nlp.annotators.Lemmatizer).  This requires a dictionary.  A pretrained Lemmatizer is apparently available as a [`PretainedModel`](https://github.com/JohnSnowLabs/spark-nlp-models).  Its use is not altogether clear.  We therefore go with the `Stemmer` for now.
// MAGIC 
// MAGIC ⓹ Asembling stemmed tokens into Ngrams is our final data-assembly step. The [`NGramGenerator`](https://nlp.johnsnowlabs.com/docs/en/annotators#ngramgenerator), with API [`NGramGenerator`](https://nlp.johnsnowlabs.com/api/#com.johnsnowlabs.nlp.annotators.NGramGenerator) serves this purpose. The `setEnableCumulative` represents its primary advantage over the [`org.apache.spark.ml.feature.NGram`](https://spark.apache.org/docs/latest/api/scala/index.html#org.apache.spark.ml.feature.NGram) transformer.  This obviates the need to concatenate tranformer outputs.
// MAGIC 
// MAGIC ⓺ One more step is needed to convert the spark-nlp output into a structure compatible with [`CountVectorizer`](https://spark.apache.org/docs/latest/api/scala/index.html#org.apache.spark.ml.feature.CountVectorizer). Tkens coming out of `spark-nlp` are wrapped in additional metadata we do not need for our LDA model. The schema is reproduced below.
// MAGIC 
// MAGIC             ```root
// MAGIC                |-- nGrams: array (nullable = true)
// MAGIC                |    |-- element: struct (containsNull = true)
// MAGIC                |    |    |-- annotatorType: string (nullable = true)
// MAGIC                |    |    |-- begin: integer (nullable = false)
// MAGIC                |    |    |-- end: integer (nullable = false)
// MAGIC                |    |    |-- result: string (nullable = true)
// MAGIC                |    |    |-- metadata: map (nullable = true)
// MAGIC                |    |    |    |-- key: string
// MAGIC                |    |    |    |-- value: string (valueContainsNull = true)
// MAGIC                |    |    |-- embeddings: array (nullable = true)
// MAGIC                |    |    |    |-- element: float (containsNull = false)   ````
// MAGIC                
// MAGIC All [`CountVectorizer`](https://spark.apache.org/docs/latest/api/scala/index.html#org.apache.spark.ml.feature.CountVectorizer) needs is the `result` attribute. This *Munging data* article  [Working with Spark ArrayType columns](https://mungingdata.com/apache-spark/arraytype-columns/) gives us a pattern to reshape our dataframes.  We use a twist on this.  
// MAGIC 
// MAGIC Specifically, we use 
// MAGIC       ```stratLitNgrams.select(col("volIssueTitleHash"),
// MAGIC                                col("nGrams.result").as("token"))```
// MAGIC method.  The `result` attribute is the `token` we need to feed the `import org.apache.spark.ml.feature.CountVectorizer()` transformer needs.
// MAGIC 
// MAGIC We colect this final result in a dataframe for which the schema appears below.  This `stratLitNgrams` dataframe has dropped all of the intermediate results from the transformer-annotator stages.  It contains only our `volIssueTitleHash` primary key and the array of `nGrams`.
// MAGIC 
// MAGIC           ```root
// MAGIC            |-- volIssueTitleHash: string (nullable = true)
// MAGIC            |-- nGrams: array (nullable = true)
// MAGIC            |    |-- element: string (containsNull = true)   ```

// COMMAND ----------

/*   
   For convenience, truncate off all of the unneded features from our `stratLitCorpus` dataframe.*/
val stratLitCorpusOnly = stratLitCorpus.select("volIssueTitleHash", "titleAbstract")
                                       .filter(length(col("titleAbstract")) >= 50)        // Filter out very-short documents.
                                                                                          // hopefully eliminates error.
/*   
   Now instantiate all of the transformer/annotators described in 🅐⓵-⓹ above. */
val stratLitDocAssy = new DocumentAssembler().setInputCol("titleAbstract")
                                             .setOutputCol("document")
                                             .setCleanupMode("shrink")
val stratLitSentence = new SentenceDetector().setInputCols(Array(stratLitDocAssy.getOutputCol))
                                             .setOutputCol("sentence")
val stratLitTokenizer = new Tokenizer().setOutputCol("token")
                                       .setInputCols(stratLitSentence.getOutputCol)
val stratListStopCleaner = new StopWordsCleaner().setInputCols(stratLitTokenizer.getOutputCol)
                                                 .setOutputCol("stopFreeTokens")
                                                 .setStopWords(workingStopWordSet)
val stratLitNormalizer = new Normalizer().setInputCols(Array(stratListStopCleaner.getOutputCol))
                                         .setOutputCol("normalized")
val stratLitStemmer = new Stemmer().setInputCols(Array(stratLitNormalizer.getOutputCol))
                                   .setOutputCol("stems")
val stratLitLemmatizer = new Lemmatizer().setInputCols(Array(stratLitNormalizer.getOutputCol))
                                         .setOutputCol("lemma")
/*   
   Construct and fit our pipelinen of transformer/annotators described in 🅐⓵-④ above. */
val stratLitAssemblyPipeline = new Pipeline().setStages(Array(stratLitDocAssy,
                                                              stratLitSentence,
                                                              stratLitTokenizer,
                                                              stratListStopCleaner,
                                                              stratLitNormalizer,
                                                              stratLitStemmer))
val stratLitTokenStemsXformer = stratLitAssemblyPipeline.fit(stratLitCorpusOnly)
var stratLitTokenStems = stratLitTokenStemsXformer.transform(stratLitCorpusOnly)
                                                  .select("volIssueTitleHash", "stems")      // Retain only the primary-key and information-bearing attributes

var docsKnownTopicStems = stratLitTokenStemsXformer.transform(docsKnownTopic)
                                                   .select("volIssueTitleHash", "stems")


// COMMAND ----------

/*
   ⓪ Initialize a single-point grid.  We configure our model to */
val ldaKGrid = List(10) //List.range(12)
val ldaNgram = List(3)
val countVecVocab = List (450)// List.range(750)
val countVecMaxDf = List(0.85)


var ldaSearchGrid = (for (ξ <- ldaKGrid; 
                          η <- countVecVocab; 
                          ζ <- countVecMaxDf; 
                          χ <- ldaNgram ) 
                     yield (ξ, η, ζ, χ)).toSeq

var ldaGridPoint = ldaSearchGrid(0)

// COMMAND ----------


/* WHERE THE REAL WORK STARTS.
   🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠 */

/*  
  ⓵ Instantiate and fot `NGramGenerator. We must transform the output in order to
     be compatible with `CountVectorizer`. Our `NGramGenerator` takes 
     a grid hyperparameter specifying `N`, the number of tokens from which to construct
     the NGrams.  */
val stratLitNgramGen = new NGramGenerator().setInputCols(Array(stratLitStemmer.getOutputCol))
                                           .setOutputCol("nGrams")
                                           .setN(ldaGridPoint._4)
                                           .setEnableCumulative(true)
                                           .setDelimiter("_")
val stratLitNgrams = stratLitNgramGen.transform(stratLitTokenStems)
                                     .select(col("volIssueTitleHash"), 
                                             col("nGrams.result").as("nGrams"))
//                                     .filter(size(col("nGrams")) > 5)  
/*  
   ⓶ Instantiate the `CountVectorizer` Transformer.  Our transformer takes two grid hyperprameters,
      one for the `vocabSize`, and one for `maxDF`.  */
val stratLitCountVecXformer = new CountVectorizer().setInputCol(stratLitNgramGen.getOutputCol) 
                                                   .setOutputCol("features")
                                                   .setVocabSize(ldaGridPoint._2)
                                                   .setMaxDF(ldaGridPoint._3)
val stratLitCountVecModel = stratLitCountVecXformer.fit(stratLitNgrams)
var stratLitCountVecs = stratLitCountVecModel.transform(stratLitNgrams)
                                             .select(col("volIssueTitleHash"),
                                                     col("features"),                     // ⓐ,ⓑ Identify distinct-token count.
                                                     distTokenCountUdf(sparseVecIndicesUdf(col("features"))).as("distinctTokenCount"))
                                              .where(col("distinctTokenCount") >= 10)    // ⓒ Filter out records below token-count threshold
                                              .select("volIssueTitleHash", "features")   // Retain only hash-key, features columns



// COMMAND ----------

/* 🍴⋔⫚⑃⑂ 
   Here we optionally fit the model or load it from a previous instance.  We
   comment out the option we elect not to pursue.*/

/*
val stratLiltLda = new LDA().setK(ldaGridPoint._1)
                            .setMaxIter(50)
                            .setOptimizer("em")
                            .setSeed(30214)                                                   // Instantiate the LDA Model.
val stratLitLdaModel = stratLiltLda.fit(stratLitCountVecs)                                    // Fit it to our corpus.
stratLitLdaModel.write
                .overwrite()
                .save("/GA_DSI10_DC-Hamlett/2004071240Zlda.mdl")                              // Save to storage for future use.
*/

val stratLitLdaModel = DistributedLDAModel.load("/GA_DSI10_DC-Hamlett/2004071240Zlda.mdl")    // Load a previously-fit model from storage.


var termCondTopicVec = stratLitLdaModel.describeTopics()                                      // Get 𝒫𝓇{Term | Topic} resulting from fitting the model.
var termCondTopicVecTop5 = stratLitLdaModel.describeTopics(maxTermsPerTopic = 5)              // Get top 5 𝒫𝓇{Term | Topic} values for each topic.
var topicCondDocVec = stratLitLdaModel.transform(stratLitCountVecs)  
                                      .withColumnRenamed("topicDistribution", "topicCondDoc") // Transform features into 𝒫𝓇{Topic | Doc}.
                                      .select("volIssueTitleHash", "topicCondDoc")
var termVocab = stratLitCountVecModel.vocabulary                                              // Get the lexicon from our CountVectorizerModel.

/*
  🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠
  WHERE THE REAL WORK ENDS. */

// COMMAND ----------

var docsKnownTopicCountVecs = stratLitCountVecModel.transform(stratLitNgramGen.transform(docsKnownTopicStems)
                                                                             .select(col("volIssueTitleHash"), 
                                                                                     col("nGrams.result").as("nGrams")))
                                             .select(col("volIssueTitleHash"),
                                                     col("features"),                     // ⓐ,ⓑ Identify distinct-token count.
                                                     distTokenCountUdf(sparseVecIndicesUdf(col("features"))).as("distinctTokenCount"))
                                              .where(col("distinctTokenCount") >= 10)    // ⓒ Filter out records below token-count threshold
                                              .select("volIssueTitleHash", "features")   // Retain only hash-key, features columns

var knownDocsTopicCondDocVecs = stratLitLdaModel.transform(docsKnownTopicCountVecs)  
                                                .withColumnRenamed("topicDistribution", "topicCondDoc") // Transform features into 𝒫𝓇{Topic | Doc}.
                                                .select("volIssueTitleHash", "topicCondDoc")

display(knownDocsTopicCondDocVecs)

// COMMAND ----------

topicCondDocVec.show

// COMMAND ----------

scala.math.exp(stratLitLdaModel.logPerplexity(stratLitCountVecs)) // This is the perplexity of our data set.


/*
  🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠🚂🐘🐂🛠 */

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC We want to calculate the conditional entropy `ℋ{Topic | Document}`. This happens in several steps.
// MAGIC 
// MAGIC 
// MAGIC ⓐ Convert our `DenseVector`-encoded `topicDistribution`s into arrays.  This StackOverflow [Convert a Spark Vector of features into an array](https://stackoverflow.com/questions/46053318/convert-a-spark-vector-of-features-into-an-array) gives a pattern.  We create a user-defined function using 
// MAGIC 
// MAGIC             `val toArr: Any => Array[Double] = _.asInstanceOf[DenseVector].toArray`
// MAGIC             `val toArrUdf = udf(toArr)   `
// MAGIC We then use it to convert the `DenseVector`-encoded conditional-probability values into arrays.  At this juncture, we apply a more-descriptive label to our columns.  The `LDA` model produces an output labeled `topicDistributions`.  This is really 𝒫𝓇{Topic | Document}.  In anticipaiton of subsequent factor-multiplications we rename this `topicCondDoc`.
// MAGIC 
// MAGIC ⓑ Introduce arrays of topic indices.  We use thsis StackOverflow pattern [Create new column with an array of range of numbers](https://stackoverflow.com/questions/51164689/create-new-column-with-an-array-of-range-of-numbers) to accomplish.  We use the `lit` literal function.
// MAGIC 
// MAGIC ⓒ Zip and explode our `topic` indices with the `topicDistribution`s.  Our primer [Topic Modeling with Latent Dirichlet Allocation](https://databricks-prod-cloudfront.cloud.databricks.com/public/4027ec902e239c93eaaa8714f173bcfc/3741049972324885/3783546674231782/4413065072037724/latest.html) gives an aproach, which we subsequently use elsewhere.   Specifically, instantiate a user-defined function `zipUDF` that casts our columns as two-tuple.  We then `explode`, which is scala's equivalent to `melt` in python or R.
// MAGIC 
// MAGIC ⓓ Separate into distinct `topic`-label and `topicDistribution` columns the couples produced by ⓒ.  This simply involves simply a SQL-like `select` operation in which we separate out the elements of the tuples into distinct columns. 
// MAGIC 
// MAGIC ⓔ We really want the conditional entropy ℋ{Document | Topic}.  This way we get an entropy for each latent topic.  To do this, we must first do a Bayesian inversion, $$\mathcal{Pr}\big(\mathrm{Document} \mid \mathrm{Topic} \big) = \frac{\mathcal{Pr}\big( \mathrm{Topic} \mid \mathrm{Document}  \big)\,  \mathcal{Pr}\big(\mathrm{Document}  \big) }{\displaystyle \sum_{\mathrm{Document}}\mathcal{Pr}\big( \mathrm{Topic} \mid \mathrm{Document}  \big)\,  \mathcal{Pr}\big(\mathrm{Document}  \big) } \text{.}$$ We accomplish this using the `Sum-product` technique described in [[D. Koller, N. Friedman, 2009, §9.3]](https://amzn.to/2Jh31cb).  We take $$ \mathcal{Pr}\big(\mathrm{Document}\big) = \frac{1}{\left\Vert \mathrm{Document} \right\Vert} \text{.}$$

// COMMAND ----------


val toArr: Any => Array[Double] = _.asInstanceOf[DenseVector].toArray
val toArrUdf = udf(toArr)
val zipUDF = udf { (topic : Seq[Int], topicDistribution : Seq[Double]) => topic.zip(topicDistribution)}



// COMMAND ----------


val toArr: Any => Array[Double] = _.asInstanceOf[DenseVector].toArray
val toArrUdf = udf(toArr)
val zipUDF = udf { (topic : Seq[Int], topicDistribution : Seq[Double]) => topic.zip(topicDistribution)}

var topicCondDoc = topicCondDocVec.select(col("volIssueTitleHash"),                                 // Assemble 𝒫𝓇{Topic | Document}
                                         toArrUdf(col("topicCondDoc")).as("topicCondDoc"))          // ⓐ Convert the DenseVector to an Array
                                  .withColumn("topic", lit(0 to ldaGridPoint._1 - 1 toArray))       // ⓑ Introduce zero-indexed topic labels
                                  .withColumn("topicCondDocZip",                 
                                              explode(zipUDF(col("topic"), col("topicCondDoc"))))   // ⓒ Zip-explode the topic labels with the distributions
                                  .select(col("volIssueTitleHash"),
                                          col("topicCondDocZip._1").as("topic"),
                                          col("topicCondDocZip._2").as("topicCondDoc"))             // ⓓ Separate out the couples produced by the zip-explode.

var topicMarg = topicCondDoc.withColumn("docMarg", lit(1/topicCondDocVec.count.toDouble))           // Stipulate 𝒫𝓇{Document} = 1/||Document||
                            .withColumn("topicMargJoint", col("docMarg") * col("topicCondDoc"))
                            .groupBy("topic")
                            .agg(sum("topicMargJoint").as("topicMarg"))                             // Calculate marginal 𝒫𝓇{Topic} from 𝒫𝓇{Topic | Document}

var docCondTopic = topicCondDoc.withColumn("docMarg", lit(1/topicCondDocVec.count.toDouble))       // Stipulate 𝒫𝓇{Document} = 1/||Document||
                               .join(topicMarg,
                                     "topic")
                               .withColumn("docCondTopic",
                                           col("topicCondDoc") * col("docMarg") / col("topicMarg")) // Bayesian inversion to 𝒫𝓇{Document | Topic}
                               .select("topic", "volIssueTitleHash", "docCondTopic")        

// COMMAND ----------

topicCondDoc.withColumn("topicEntropyCondDoc",
                        -col("topicCondDoc") * log(ldaGridPoint._1, col("topicCondDoc")))
            .groupBy("volIssueTitleHash")
            .agg(sum("topicEntropyCondDoc").as("topicEntropyCondDoc")).describe("topicEntropyCondDoc").show

// COMMAND ----------

display(knownDocsTopicCondDocVecs.select(col("volIssueTitleHash"),                                 // Assemble 𝒫𝓇{Topic | Document}
                                         toArrUdf(col("topicCondDoc")).as("topicCondDoc"))          // ⓐ Convert the DenseVector to an Array
                                  .withColumn("topic", lit(0 to ldaGridPoint._1 - 1 toArray))       // ⓑ Introduce zero-indexed topic labels
                                  .withColumn("topicCondDocZip",                 
                                              explode(zipUDF(col("topic"), col("topicCondDoc"))))   // ⓒ Zip-explode the topic labels with the distributions
                                  .select(col("volIssueTitleHash"),
                                          col("topicCondDocZip._1").as("topic"),
                                          col("topicCondDocZip._2").as("topicCondDoc")))             // ⓓ Separate out the couples produced by the zip-explode.
                                

// COMMAND ----------

docCondTopic.withColumn("docEntropyCondTopic",
                        -col("docCondTopic") * log(stratLitCountVecs.count.toDouble, col("docCondTopic")))
            .groupBy("topic")
            .agg(sum("docEntropyCondTopic")).show()

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC val zipUDF = udf { (terms: Seq[String], probabilities: Seq[Double]) => terms.zip(probabilities) }
// MAGIC 
// MAGIC topics.withColumn("termWithProb", explode(zipUDF(col("terms"), 
// MAGIC                                                  col("termWeights")))).show()

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC #### Old documentation
// MAGIC 
// MAGIC Below we walk through the steps of a data-assembly pipeline. We are going to mix stages from `org.apache.spark.ml.feature` and `com.johnsnowlabs.nlp.annotator` packages. [Spark-NLP: Getting started in Databricks](https://johnsnowlabs.github.io/spark-nlp-workshop/databricks/index.html#Getting%20Started.html) contains an example with a pre-built pipeline.  This however is largely a black box.  We don't know what's inside.  Now, the key apears to be that the `com.johnsnowlabs.nlp.annotator` packages require additional metadata.  We need a coule of transformers before we get to tokenization.  Explanation appears at [Transformers](https://nlp.johnsnowlabs.com/docs/en/transformers). This medium.com article [Spark NLP Walkthrough, powered by TensorFlow](https://medium.com/@saif1988/spark-nlp-walkthrough-powered-by-tensorflow-9965538663fd) shows the process step-by-step.  
// MAGIC 
// MAGIC ⓵ **Tokenization**. We use the `Tokenizer()` class from `org.apache.spark.ml.feature` package. ProgramCreek [org.apache.spark.ml.feature.Tokenizer Scala Examples](https://www.programcreek.com/scala/org.apache.spark.ml.feature.Tokenizer) demonstrates the syntax.
// MAGIC 
// MAGIC ⓶ **Normalizer**. We remove "dirty characters" using `com.johnsnowlabs.nlp.annotator`. [Normalizer](https://github.com/JohnSnowLabs/spark-nlp/blob/master/docs/en/annotators.md#normalizer) provides illustrations of the syntax. A detailed explanation of the API appears at [class Normalize](https://nlp.johnsnowlabs.com/api/index#com.johnsnowlabs.nlp.annotators.Normalizer).
// MAGIC 
// MAGIC 
// MAGIC ⓵⓶⓷⓸⓹⓺

// COMMAND ----------

val zipUDF = udf { (terms: Seq[String], probabilities: Seq[Double]) => terms.zip(probabilities) }


// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC ### Model-ouptput processing. 
// MAGIC 
// MAGIC We want three artifacts from our model.  These are:
// MAGIC 
// MAGIC 🅐 A `DataFrame` of `(volIssueTitleHash, topicDistEntropy)` pairs.  We calculate this in Scala and export the `DataFrame` as a `csv` file.
// MAGIC 
// MAGIC 🅑 A `Map` of `(volIssueTitleHash, topicDistribution)` items.  We construct a `Map`, which we transform to a `String` and export as a `json` file.  Courtesy of Riley Dallas, this StackOverflow [How to convert a List of Lists to a DataFrame in Scala?](https://stackoverflow.com/questions/50982722/how-to-convert-a-list-of-lists-to-a-dataframe-in-scala) contains a somewhat-crude pattern.
// MAGIC 
// MAGIC 🅒 A `DataFrame` of `(topicId, term, probability)` triples.  We export this as a `csv` file.
// MAGIC 
// MAGIC Now, `LDA().transform()` method produces a datframe whose `topicDistribution` probabilities are in `denseVector` format.  Extracting content from dataframes can troublesome, in general.  This StackOverflow [How to convert a Dataframe into a List (Scala)?](https://stackoverflow.com/questions/55828702/how-to-convert-a-dataframe-into-a-list-scala) describes a possibly-tractable approach.  This [Extract column values of Dataframe as List in Apache Spark](https://stackoverflow.com/questions/32000646/extract-column-values-of-dataframe-as-list-in-apache-spark) talks about converting a column to a list.
// MAGIC 
// MAGIC Things come out of `DataFrame` objects as type `Any`.  Nothing can be done with an `Any` without first casting it into a more-specific data type.  This StackOverflow [Cast Any to an Array [duplicate]](https://stackoverflow.com/questions/50628277/cast-any-to-an-array) gives us a procedure.  We wrap this with [Spark: Applying UDF to Dataframe Generating new Columns based on Values in DF](https://stackoverflow.com/questions/42643737/spark-applying-udf-to-dataframe-generating-new-columns-based-on-values-in-df) in order to get the `any : denseVector`-format `topicDistribution` column produced by the `LDA().transform` into an array from which we can calculate entropy.

// COMMAND ----------

val setA = List(1,2,3)
val setB = List("a", "b")
val setC = List("😃", "🤮")
(for (x <- setA; y <- setB; z <- setC) yield (x, y, z))

// COMMAND ----------

val termsIdx2Str = udf { (termIndices: Seq[Int]) => termIndices.map(idx => termVocab(idx)) }
val topics = termCondTopicVecTop5.withColumn("terms", termsIdx2Str(col("termIndices")))
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

/*  
    Here we conduct the complete 𝒫𝓇{Term | Document} and export to csv. */
val  termCondTopic = termCondTopicVec.withColumn("terms", termsIdx2Str(col("termIndices")))
                                    .withColumn("termWithProb", explode(zipUDF(col("terms"), col("termWeights"))))
                                    .select(col("topic").as("topicId"),
                                            col("termWithProb._1").as("term"),
                                            col("termWithProb._2").as("probability"))
                                    .withColumnRenamed("probability", "termCondTopic")
termCondTopic.repartition(1) 
              .write
              .format("com.databricks.spark.csv")
              .mode("overwrite")
              .option("header", "false")
              .save("/GA_DSI10_DC-Hamlett/termCondTopic.csv")

display(termCondTopic)

// COMMAND ----------

termCondTopic.withColumn("termEntropyCondTopic",
                         -col("termCondTopic") * log(ldaGridPoint._2.toDouble, col("termCondTopic")))
              .groupBy("topicId")
              .agg(sum("termEntropyCondTopic")).show

// COMMAND ----------

// Create JSON data
val rawJson = termDF.withColumnRenamed("termCondTopic", "probability")
                    .toJSON
                    .collect()
                    .mkString(",\n")


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

