package main

import etl.objDataProcessing
import shapeless.Data
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.Row
import spire.algebra.CoordinateSpace
import org.apache.spark.mllib.linalg.distributed.CoordinateMatrix

object objTestPredictions {
    println("Test Predictions..")
    val spark = SparkSession
        .builder()
        .appName("Instacart Prediction Project")
        .config("spark.master", "local[*]")
        .getOrCreate()
    import spark.implicits._
    
    spark.sparkContext.setLogLevel("ERROR") //To avoid warnings

    def generateSimilarItems(testDf:DataFrame, similarityDf:DataFrame,processedDf:DataFrame,method:String="cosine" ) = {
        
        val singleSample = testDf.limit(1)       
        print(s"Test Product Id:\n")
        singleSample.select("product_id","product_name").show()
        method match{
            case "cosine" => 
                println("\nproducts based on similarities \n")
                /*
                val simDf = similarityDf.alias("smdf")
                            .join(processedDf.select("product_id","product_name")
                                .withColumnRenamed("product_name","product_name_left")
                                .alias("fulldf"),col("smdf.product_id_left")=== col("fulldf.product_id"))
                            .drop(col("fulldf.product_id"))
                            .join(processedDf.select("product_id","product_name")
                                .withColumnRenamed("product_name","product_name_right")
                                .alias("fulldf2"),col("smdf.product_id_right")=== col("fulldf2.product_id"))
                            .drop(col("fulldf2.product_id"))
                */
                //simDf.show(10)
                val productsDf = objDataProcessing.readCSV("data/products.csv")
                val simDf = similarityDf.alias("smdf")
                            .join(productsDf.select("product_id","product_name")
                                .withColumnRenamed("product_name","product_name_left")
                                .alias("pdf"),col("smdf.product_id_left")=== col("pdf.product_id"))
                            .drop(col("pdf.product_id"))
                            .join(productsDf.select("product_id","product_name")
                                .withColumnRenamed("product_name","product_name_right")
                                .alias("pdf2"),col("smdf.product_id_right")=== col("pdf2.product_id"))
                            .drop(col("pdf2.product_id"))

                val sampleSimilarities = simDf.alias("smdf")
                                    .join(singleSample.select("product_id","product_name")
                                    .alias("singleSample"),
                                    col("smdf.product_id_right") === col("singleSample.product_id"),"inner")
                                    .select("product_id_left","product_id_right","product_name_left","product_name_right","cosine_similarity")
                                    .sort($"cosine_similarity".desc)
                        
                println("\nshowing top 30 similar products:\n")
                sampleSimilarities.show(30, false)
           
            case "cooccurance" => 
                    println("\nSimilar products based on Cooccurances \nOur Cooccurance Matrix: ")
                    similarityDf.show(10)
                    val sampleSimilarities = similarityDf.alias("smdf")
                                    .join(singleSample.select("product_id","product_name")
                                    .alias("singleSample"),
                                    col("smdf.product_id_right") === col("singleSample.product_id"),"inner")
                                    .drop("singleSample.product_id","singleSample.product_name")
                                    .sort($"cooccurances".desc)
                        
                println("\nshowing top 30 similar products:\n")
                sampleSimilarities.show(30, false)
                    
        }

    }
}