package com.tfs.flashml.core

import com.tfs.flashml.util.{ConfigValues, FlashMLConfig}
import com.tfs.flashml.util.conf.FlashMLConstants
import org.apache.hadoop.fs.Path
import org.apache.spark.ml.{PipelineModel, Transformer}
import org.apache.spark.sql.DataFrame
import org.slf4j.Logger

import scala.collection.mutable.ArrayBuffer

/**
  * Traits for individual steps in model building process.
  *
  * @since 22/8/18
  */
trait Engine
{
    val log: Logger

    val pipelineModelArray: ArrayBuffer[PipelineModel] = new ArrayBuffer[PipelineModel]()

    val outputDFArrayBuffer: ArrayBuffer[DataFrame] = new ArrayBuffer[DataFrame]()

    val basePath: Path = DirectoryCreator.getModelPath()

    /**
      * @param df        =  Dataframe on which the pipeline is fit
      * @param pageCount Values should be 0 for non page level and page number for Page Level model
      * @return PipelineModel containing the preprocessing steps.
      */
    def buildPipelineModel(df: DataFrame, pageCount: Int): PipelineModel

    /**
      * Save Pipeline Model Object on disk
      *
      * @param pageNumber 0 for single intent, Page Number for page level models
      */
    def savePipelineModel(pipelineModel: PipelineModel, pageNumber: Int = 0, step: String): Unit =
    {
        val pageString: String = if (pageNumber == 0) "noPage" else "page" + pageNumber
        val pipelinePath = FlashMLConfig.getString(FlashMLConstants.NAME_NODE_URI) +
                s"$basePath/$pageString/noSegment/pipeline/${step}_pipeline"
        pipelineModel
                .write
                .overwrite()
                .save(pipelinePath)
        log.info(s"Saved ${step.capitalize} pipeline [$pageString] at [$pipelinePath]")
    }

    /**
      * Load pipeline model object
      *
      * @param pageNumber 0 for single intent, Page Number for page level models
      */
    def loadPipelineModel(pageNumber: Int, step: String): PipelineModel =
    {
        loadPipelineModelChecked(pageNumber, step).get
    }

    /**
      * Checked method to load a pipeline model object, since some of the steps may not exist.
      * @param pageNumber
      * @param step
      * @return
      */
    def loadPipelineModelChecked(pageNumber: Int, step: String): Option[PipelineModel] =
    {
        val pageString: String = if (pageNumber == 0) "noPage" else "page" + pageNumber
        val pipelinePath = FlashMLConfig.getString(FlashMLConstants.NAME_NODE_URI) +
                s"$basePath/$pageString/noSegment/pipeline/${step}_pipeline"
        // Some steps, although mentioned in the config file, might be skipped because nothing is mentioned
        // in the config, e.g., a vectorization block with no/empty options would skip the step, and would
        // not save anything in the corresponding folder. So, we need to check first if the folder exists.
        val pipelineModel = if (ConfigValues.fs.exists(new Path(pipelinePath)))
        {
            val plModel = PipelineModel.load(pipelinePath)
            log.info(s"Loaded ${step.capitalize} pipeline [$pageString] from [$pipelinePath]")
            Some(plModel)
        }
        else
        {
            log.info(s"Skipped ${step.capitalize} pipeline [$pageString]: Couldn't locate [$pipelinePath]")
            None
        }
        pipelineModel
    }
}
