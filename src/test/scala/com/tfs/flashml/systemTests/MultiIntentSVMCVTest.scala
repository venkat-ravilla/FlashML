package com.tfs.flashml.systemTests

import com.tfs.flashml.core.PipelineSteps
import com.tfs.flashml.dal.SavePointManager
import com.tfs.flashml.util.{ConfigValues, FlashMLConfig}
import com.tfs.flashml.util.conf.FlashMLConstants
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.mllib.evaluation.MulticlassMetrics
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.scalatest.flatspec.AnyFlatSpec
import org.slf4j.LoggerFactory

/**
 * MultiIntentSVMCVTest
 */
class MultiIntentSVMCVTest extends AnyFlatSpec{

  private val log = LoggerFactory.getLogger(getClass)
  Logger.getLogger("org").setLevel(Level.OFF)
  Logger.getLogger("breeze").setLevel(Level.OFF)

  //Load application.conf
  val configSolutionsKeyPair: Config = ConfigFactory.load()

  FlashMLConfig.config= ConfigFactory.load("multiIntent_svm_cv_test_config.json")

  val appName = s"${FlashMLConfig.getString(FlashMLConstants.FLASHML_PROJECT_ID)}/${FlashMLConfig.getString(FlashMLConstants.FLASHML_MODEL_ID)}/${FlashMLConfig.getString(FlashMLConstants.FLASHML_JOB_ID)}"
  val context = FlashMLConfig.getString(FlashMLConstants.CONTEXT)
  val HIVE_METASTORE_KEY = "hive.metastore.uris"
  val HIVE_METASTORE_THRIFT_URL = FlashMLConfig.getString(FlashMLConstants.HIVE_THRIFT_URL)

  val spark = SparkSession.builder()
    .config(HIVE_METASTORE_KEY, HIVE_METASTORE_THRIFT_URL)
    .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    .config("spark.kryo.registrator", "com.tfs.flashml.util.FlashMLKryoRegistrator")
    .config("spark.extraListeners","com.tfs.flashml.util.CustomSparkListener")
    .config("spark.kryoserializer.buffer.max", "256")
    .config("spark.sql.parquet.compression.codec", "gzip")
    .config("spark.ui.showConsoleProgress", "False")
    .master(context)
    .appName(appName)
    .enableHiveSupport().getOrCreate()

  //Test cases for binary logistic regression
  println("=============================================================================================")
  log.info("Starting FlashML test application")
  println("Test case: MultiIntent SVM with CV")

  //FlashMLConfig.config = ConfigFactory.load("binary_test_config.properties")

  PipelineSteps.run()

  import spark.implicits._

  var multiIntentSVMCVPredictionDF: Array[RDD[(Double,Double)]] = SavePointManager
          .loadData(FlashMLConstants.SCORING)
          .map(_.select("prediction", ConfigValues.getIndexedResponseColumn).as[(Double, Double)].rdd)


  val multiIntentSVMCVEvaluatorTrain = new MulticlassMetrics(multiIntentSVMCVPredictionDF(0))

  "SVM-MultiIntent-CV-Train-Precision" should "match" in {
    withClue("SVM-MultiIntent-Train-Precision: ") {
      assertResult(configSolutionsKeyPair.getString("FlashMLTests.multiIntentSVMCV.trainPrecision").toDouble) { multiIntentSVMCVEvaluatorTrain.weightedPrecision
      }
    }
  }

  "SVM-MultiIntent-CV-Train-Recall" should "match" in {
    withClue("SVM-MultiIntent-Train-Recall: ") {
      assertResult(configSolutionsKeyPair.getString("FlashMLTests.multiIntentSVMCV.trainRecall").toDouble) {
        multiIntentSVMCVEvaluatorTrain.weightedRecall
      }

    }
  }

  val multiIntentSVMCVEvaluatorTest = new MulticlassMetrics(multiIntentSVMCVPredictionDF(1))

  "SVM-MultiIntent-CV-Test-Precision" should "match" in {
    withClue("SVM-MultiIntent-Test-Precision: ") {
      assertResult(configSolutionsKeyPair.getString("FlashMLTests.multiIntentSVMCV.testPrecision").toDouble) { multiIntentSVMCVEvaluatorTest.weightedPrecision
      }

    }
  }

  "SVM-MultiIntent-CV-Test-Recall" should "match" in {
    withClue("SVM-MultiIntent-Test-Recall: ") {
      assertResult(configSolutionsKeyPair.getString("FlashMLTests.multiIntentSVMCV.testRecall").toDouble) { multiIntentSVMCVEvaluatorTest.weightedRecall
      }
    }
  }

}
