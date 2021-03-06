package com.tfs.flashml.core.modeltraining

import com.tfs.flashml.core.sampling.{StratifiedTrainTestSplitter, TrainTestSampler}
import com.tfs.flashml.core.{Engine, Validator}
import com.tfs.flashml.util.ConfigValues.{isUplift, upliftColumn}
import com.tfs.flashml.util.conf.{ConfigValidatorException, FlashMLConstants}
import com.tfs.flashml.util.{ConfigValues, FlashMLConfig}
import org.apache.spark.SparkException
import org.apache.spark.ml._
import org.apache.spark.ml.classification._
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator
import org.apache.spark.ml.feature._
import org.apache.spark.ml.tuning.generators.RandomParamSetGenerator
import org.apache.spark.ml.tuning.{CrossValidatorCustom, HyperBand}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.col
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.ArrayBuffer

/**
  * Fit the appropriate model on the dataframes
  */
object ModelTrainingEngine extends Engine with Validator
{
    override val log: Logger = LoggerFactory.getLogger(getClass)
    val columnsNames = (ConfigValues.primaryKeyColumns ++ Array(ConfigValues.responseColumn,FlashMLConstants.FEATURES))
            .filter(_.nonEmpty)
            .distinct

    private val seedValue : Int = 999

    private val algorithm: String = ConfigValues.mlAlgorithm

    def fit(odfArray: Option[Array[DataFrame]]): Option[Array[PipelineModel]] =
    {
        odfArray.map(dfArray =>
        {
            dfArray.map(_.select(columnsNames.map(col): _*).cache)

            val intermediateDF = ArrayBuffer[DataFrame]()

            val minorityClassPercentRequired: Int = FlashMLConfig.getInt(FlashMLConstants.MINORITY_CLASS_PERCENT)
            if (minorityClassPercentRequired >= 50)
                throw new SparkException("Positive class percent mentioned in the config for data balance should be less than 50")

            if (ConfigValues.isModel)
            {
                if (ConfigValues.isPageLevelModel)
                {
                    (1 to ConfigValues.numPages).foreach
                    { pageNumber: Int =>
                        if (ConfigValues.isSingleIntent)
                        {
                            // Positive Class Validation
                            log.info(s"Running positive class validation for page $pageNumber training data")
                            TrainTestSampler.validateMinorityClass(dfArray(pageNumber - 1))

                            // Data Balance
                            if (!minorityClassPercentRequired.equals(0) &&
                                FlashMLConfig.getString(FlashMLConstants.BUILD_TYPE).toLowerCase == FlashMLConstants.BUILD_TYPE_BINOMIAL)
                            {
                                // Data Balance
                                log.info(s"Running data balance for page $pageNumber training data")
                                intermediateDF.append(TrainTestSampler.dataBalance(dfArray(pageNumber - 1),
                                    FlashMLConfig.getString(FlashMLConstants.SAMPLING_TYPE)))
                            }
                            else
                                intermediateDF.append(dfArray(pageNumber - 1))

                            pipelineModelArray += buildPipelineModel(intermediateDF(pageNumber - 1), pageNumber)
                        }
                        else pipelineModelArray += buildPipelineModel(dfArray(pageNumber - 1), pageNumber)
                    }
                }
                else
                {
                    if (ConfigValues.isSingleIntent)
                    {
                        // Positive Class Validation
                        log.info(s"Running positive class validation for training data")
                        TrainTestSampler.validateMinorityClass(dfArray(0))

                        if (!minorityClassPercentRequired.equals(0) && FlashMLConfig
                                .getString(FlashMLConstants.BUILD_TYPE) == FlashMLConstants.BUILD_TYPE_BINOMIAL)
                        {
                            // Data Balance
                            intermediateDF.append(TrainTestSampler
                                    .dataBalance(dfArray(0), FlashMLConfig.getString(FlashMLConstants.SAMPLING_TYPE)))
                        }
                        else
                            intermediateDF.append(dfArray(0))

                        pipelineModelArray += buildPipelineModel(intermediateDF(0), 0)
                    }
                    else pipelineModelArray += buildPipelineModel(dfArray(0), 0)
                }
                dfArray.map(_.unpersist())
                pipelineModelArray.toArray
            }
            else loadPipelineArray
        })
    }

    /**
     * Method to load the PipelineModel objects.
     * @return
     */
    def loadPipelineArray: Array[PipelineModel] =
    {
        if (ConfigValues.isPageLevelModel)
        {
            (1 to ConfigValues.numPages).foreach(pageNumber => pipelineModelArray += loadPipelineModel(pageNumber))
        }
        else pipelineModelArray += loadPipelineModel(0)

        pipelineModelArray.toArray
    }

    /**
     * Method to build the PipelineModel object
     * @param df        Dataframe on which the pipeline is fit
     * @param pageCount Values should be 0 for non page level and page number for Page Level model
     * @return PipelineModel containing the preprocessing steps.
     */
    override def buildPipelineModel(df: DataFrame, pageCount: Int): PipelineModel =
    {
        val allStages = ArrayBuffer[PipelineStage]()

        log.info(s"Model Training: Adding String Indexer to the pipeline")

        // String Indexer for indexing response variable
        val responseColumnIndexer = if (ConfigValues.isSingleIntent)
        {
            new StringIndexer()
                    .setInputCol(ConfigValues.responseColumn)
                    .setOutputCol(ConfigValues.getIndexedResponseColumn)
                    .setHandleInvalid("skip")
        }
        else
        {
            new StringIndexer()
                    .setInputCol(ConfigValues.responseColumn)
                    .setOutputCol(ConfigValues.getIndexedResponseColumn)
                    .setStringOrderType("alphabetAsc")
                    .setHandleInvalid("skip")
        }

        allStages += responseColumnIndexer

        // Get ML Estimator
        val crossValidationFolds = FlashMLConfig.getInt(FlashMLConstants.CROSS_VALIDATION)

        val doCrossValidate = crossValidationFolds > 1

        val estimator: Estimator[_] = if (isHyperParam)
        {
            //log.info("hyperparameter experiment")
            // hyperband is initialized as part of the validate step itself
            hyperband.getOrElse(getHyperParamOp)
        }
        else if (doCrossValidate) getCrossValidator(crossValidationFolds.toInt)
        else ModelTrainingUtils.getEstimator
        log.info(s"Model Training: Adding ${estimator.getClass.getSimpleName} to the pipeline")
        allStages += estimator

        val isPlattScalingRequired = ConfigValues.isPlattScalingReqd
        if (isPlattScalingRequired)
        {
            log.info(s"Model Training: Adding PlattScaler to the pipeline")
            allStages += new PlattScalar()
                    .setIsMultiIntent(ConfigValues.isMultiIntent)
                    .setLabelCol(ConfigValues.getIndexedResponseColumn)
                    .setParallelism(ConfigValues.flashmlParallelism)
        }

        // Uplift and TopKIntents require fitted models.
        val intermediatePipeline = new Pipeline()
                .setStages(allStages.toArray)

        val intermediateModel = intermediatePipeline.fit(df)

        log.debug("Model Training: Fitted intermediate pipeline")

        // Add Uplift, if required
        if (ConfigValues.isUplift)
        {
            if (ConfigValues.isMultiIntent)
                throwException("Uplift Transformation is not applicable for Multi Intent Models")
            else
            {
                log.info(s"Model Training: Adding Uplift to the pipeline")
                val estimatorModel = intermediateModel
                        .stages(1)
                        .asInstanceOf[Model[_]]

                val uplift = new UpliftTransformer()
                        .setBaseClassifier(estimatorModel)

                if (isPlattScalingRequired)
                    uplift
                            .setPlattScalar(intermediateModel
                                    .stages(2)
                                    .asInstanceOf[PlattScalarModel])

                allStages += uplift
            }
        }

        //todo replace Probability related checks with fetching value from DF
        // Not possible as the Df is within the same pipeline step. Unable to access reqd DF

        /*
        lazy val isTopKPossible = ConfigUtils.isMultiIntent && !ConfigUtils.topKIntentColumnName.isEmpty &&
                (FlashMLConstants.probabilitySupportedAlgorithms.contains(algorithm) || isPlattScalingRequired || algorithm == FlashMLConstants.LOGISTIC_REGRESSION) */

        // Add Top K Intent Derivation, if required
        if (ConfigValues.isTopKPossible)
        {
            val stringIndexerModel = intermediateModel
                    .stages(0)
                    .asInstanceOf[StringIndexerModel]

            val topKIntents = new TopKIntents()
                    .setInputCol()
                    .setKValue(ConfigValues.topKValue)
                    .setOutputCol(ConfigValues.topKIntentColumnName)
                    .setLabels(stringIndexerModel.labels)

            log.info(s"Model Training: Adding TopK Intent model to the pipeline")
            allStages += topKIntents
        }

        // Add IndexToString transformer to convert prediction indexes to corresponding labels
        if (!ConfigValues.isSingleIntent)
        {
            val stringIndexerModel = intermediateModel
                    .stages(0)
                    .asInstanceOf[StringIndexerModel]

            val predictionStringifier = new IndexToString()
                    .setInputCol(FlashMLConstants.PREDICTION)
                    .setOutputCol(FlashMLConstants.PREDICTION_LABEL)
                    .setLabels(stringIndexerModel.labels)

            log.info(s"Model Training: Adding IndexToString transformer to the pipeline")
            allStages += predictionStringifier
        }

        // Build Pipeline - intermediateModel contain the fitted models. Fitting the models again is expensive.
        // In the case of Platt Scaling, we have StringIndexer, SVM, then Platt Scalar transformers fitted onto DF
        // In the case of no Platt Scaling, we have only StringIndexer and LR algorithm transformers fitted onto DF
        // Based on whether or not Platt Scaling was used, we remove those transformers from the pipeline before
        // re-fitting PipelineModel on DF
        // Hence, we only fit the additional stages
        val modelTrainingPipeline = if (isPlattScalingRequired)
        {
            if (allStages.size > 3)
                new Pipeline().setStages(intermediateModel.stages ++ allStages.drop(3))
            else
                new Pipeline().setStages(intermediateModel.stages)
        }
        else
        {
            if (allStages.size > 2)
                new Pipeline().setStages(intermediateModel.stages ++ allStages.drop(2))
            else
                new Pipeline().setStages(intermediateModel.stages)
        }

        // Fit the pipeline of the dataframe and then save
        val trainedModel = modelTrainingPipeline.fit(df)
        // Save the model training pipeline
        savePipelineModel(trainedModel, pageCount)
        trainedModel
    }

    private def getCrossValidator(crossValidationFolds: Int): CrossValidatorCustom =
    {
        val evaluator = new MulticlassClassificationEvaluator()
                .setMetricName(ConfigValues.cvEvalMetric)
                .setLabelCol(ConfigValues.getIndexedResponseColumn)

        val estimator = ModelTrainingUtils.getEstimator

        val paramGrid = ModelTrainingUtils.getParamGridFlashML(estimator)

        new CrossValidatorCustom()
                .setEstimator(estimator)
                .setEvaluator(evaluator)
                .setEstimatorParamMaps(paramGrid)
                .setNumFolds(crossValidationFolds)
                .setParallelism(ConfigValues.flashmlParallelism)
                .setSeed(seedValue)
    }

    private val savePipelineModel: (PipelineModel, Int) => Unit = savePipelineModel(_: PipelineModel, _: Int, FlashMLConstants.MODEL_TRAINING)

    val loadPipelineModel: Int => PipelineModel = loadPipelineModel(_: Int, FlashMLConstants.MODEL_TRAINING)

    def throwException(msg: String) =
    {
        log.error(msg)
        throw new SparkException(msg)
    }

    private var hyperband:Option[Estimator[_]] = None

    /**
      * validates the Model related config
      */
    override def validate(): Unit =
    {
        if (isUplift && upliftColumn.isEmpty){
            val msg = s"Treatment variable needs to be provided in uplift transformation."
            throw new ConfigValidatorException(msg)
        }

        //TODO can later just perform the validation steps here, rather than creation of hyperband estimator
        if (isHyperParam)
        {
            this.hyperband = Some(getHyperParamOp)
        }
    }

    private def isHyperParam = FlashMLConfig.hasKey(FlashMLConstants.HYPER_PARAM_OP) && FlashMLConfig.getBool(FlashMLConstants.HYPER_PARAM_OP)

    private def getHyperParamOp:HyperBand = {

        def getAlgoName(algoStr: String) = algoStr
                .substring(algoStr.lastIndexOf(".") + 1)

        val evaluator = new MulticlassClassificationEvaluator()
                .setMetricName("weightedPrecision")
                .setLabelCol(ConfigValues.getIndexedResponseColumn)

        val estimator = ModelTrainingUtils.getEstimator
        val algoName = getAlgoName(estimator.getClass.getCanonicalName)
        val responseVariable = ConfigValues.getIndexedResponseColumn

        // max iterations to be used in hyperband, setting a default value of 1000. This value is updated based on
        // configuration specified for each algorithm
        val maxIterations = 1000

        val (maxIterV,paramRangeSpec) = ModelTrainingUtils.getHyperBandParamRange(estimator, maxIterations,algoName)

        var hyperband = new HyperBand()
                .setEstimator(estimator)
                .setEvaluator(evaluator)
                .setMaxIterationsFinalModel(maxIterV)
                .setParamGenerator(new RandomParamSetGenerator(paramRangeSpec,seedValue))
                .setParallelism(ConfigValues.flashmlParallelism)
                .setSeed(seedValue)

        // Following keys are optional
        if(FlashMLConfig.hasKey(FlashMLConstants.HYPERBAND_ITERATIONS)){
            hyperband = hyperband.setMaxHyperbandIter(FlashMLConfig.getInt(FlashMLConstants.HYPERBAND_ITERATIONS))
        }
        if(FlashMLConfig.hasKey(FlashMLConstants.HYPERBAND_ITER_MULTIPLIER)){
            hyperband = hyperband.setIterationMultiplier(FlashMLConfig.getInt(FlashMLConstants.HYPERBAND_ITER_MULTIPLIER))
        }
        if(FlashMLConfig.hasKey(FlashMLConstants.HYPERBAND_ETA)){
            hyperband = hyperband.setEta(FlashMLConfig.getInt(FlashMLConstants.HYPERBAND_ETA))
        }
        if(FlashMLConfig.hasKey(FlashMLConstants.HYPERBAND_TRAIN_SIZE)){
            hyperband = hyperband
                    .setTrainTestSplitter(new StratifiedTrainTestSplitter(FlashMLConfig.getDouble(FlashMLConstants.HYPERBAND_TRAIN_SIZE),responseVariable,seedValue))
        }

        hyperband
    }

}
