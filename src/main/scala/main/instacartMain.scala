package main

import etl.objDataProcessing
import org.apache.log4j.Logger
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.mllib.linalg.distributed._

class instacartMain extends Serializable {
  @transient lazy val logger: Logger = Logger.getLogger(getClass.getName)
}

object instacartMain extends Serializable{

  val spark = SparkSession
        .builder()
        .appName("Instacart Prediction Project")
        .config("spark.master", "local[*]")
        .getOrCreate()
    import spark.implicits._
    spark.sparkContext.setLogLevel("ERROR") //To avoid warnings
    val sqlContext = spark.sqlContext

  def main(args: Array[String]): Unit = {

    println("Initialising main...")
    val filteredDfPath = "data/filteredf.parquet"
    val fullProcessedDfPath = "data/fullOrderProductsDf.parquet"
    val filteredDfCooccurances = "data/filteredDfCooccurances.csv"
    val fullDfCooccurances = "data/fullDfCooccurances.csv"
    val noOfTestSamples = 5
    val saveItemSimMatPath = "data/itemSimilarityMatrix.txt"
    val similarityDfCsvPath = "data/productSimilarities.csv"
    val similarityDfParquetPath = "data/productSimilarities.parquet"
    
  
/*
    Run first 2 steps only once, it writes out a processed parquet file to be used for createItemMatrixDF()
*/  
    //========================================================================
    // Step 1: Load and merge csv files
    //val processedDf = objDataProcessing.ingestAndProcessData(fullProcessedDfPath)

    //========================================================================
    //step 2: Generate a subset of the sample for dev purpose and save subset for dev purposes
    
    //val filteredDf = objDataProcessing.generateDepWiseSample(processedDf)
    //objDataProcessing.writeToParquet(filteredDf,filteredDfPath)

    //========================================================================
    //Step 3 : Load processed data, by default it'll load the subset of the full dataset, dataset of department alcohol
    //use args[0] for commandline paths
    //val processedDf = objDataProcessing.getParquet(filteredDfPath)
    val processedDf = objDataProcessing.getParquet(filteredDfPath)
    //generate testSamples
    val testItemsDf = processedDf.sample(true, 0.1).limit(noOfTestSamples).toDF()
    
    //========================================================================
    //generate Cooccurances
    val (cooccuranceDf,cooccuranceMat) = objItemMatrix.generateCooccurances(processedDf,filteredDfCooccurances)

  //========================================================================
  //Decompose the cooccurance matrix using SVD 
    objModels.applySVD(cooccuranceMat,testItemsDf)
    
  //========================================================================
    //Step: train ALS algorithm on cooccurance Matrix
    objModels.applyItemItemALS(cooccuranceDf,testItemsDf)




    //========================================================================
    //Step : Generate Similarties BETWEEN PRODUCTS using Cosine Similarities
    //Similarities based on correlation of purchases.
    //SIMILARITIES GENERATED,took, total 47 Mins to generate similarities and save as CSV
    //val similarityDf = objCosineSimilarity.generateCosineSimilartyWithoutMatrix(processedDf,saveItemSimMatPath)

    //Reading already generated similarity ratings
    //val similarityDf = objDataProcessing.readCSV(similarityDfCsvPath)

    //========================================================================
    //step : Check for Similar Items, using generated similarities
    //objTestPredictions.generateSimilarItems(testItemsDf.select("product_id","product_name"),similarityDf)
    
    //========================================================================
    //step n: Stop spark session before finishing
    objDataProcessing.spark.stop()



  }
}


